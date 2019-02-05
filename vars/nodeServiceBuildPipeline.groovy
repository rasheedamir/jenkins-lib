def call(configMap) {

    def credentialId = 'dd_ci'

    def kubeConfig = params.KUBE_CONFIG
    def dockerUrl = params.DOCKER_URL
    def mavenRepo = params.MAVEN_REPO
    def secondaryMavenRepo = params.SECONDARY_MAVEN_REPO

    def buildVersion
    def scmVars
    def newImageName
    def project

    def isMergeRequestBuild = params.IS_MERGE_REQUEST_BUILD ?: false
    echo "isMergeRequestBuild: ${isMergeRequestBuild}"

    assert !(isMergeRequestBuild && env.gitlabSourceBranch == null)

    def branchName = isMergeRequestBuild ? env.gitlabSourceBranch : env.gitlabBranch ?: 'master'
    def deployToDevAndProd = !(isMergeRequestBuild)
    def mr_version_postfix = "-beta"

    timestamps {
        withSlackNotificatons() {
            try {

                gitlabBuilds(builds: ["Build", "System test"]) {
                    podTemplate(volumes: [secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube')]) {

                        gitlabCommitStatus(name: "Build") {

                            dockerNode(dockerImage: 'stakater/frontend-tools:0.1.0-8.12.0') {

                                def js_package

                                container(name: 'docker') {
                                    stage("checkout") {
                                        scmVars = checkout([$class: 'GitSCM', branches: [[name: branchName]], userRemoteConfigs: scm.getUserRemoteConfigs()])
                                        js_package = readJSON file: 'package.json'
                                        project = js_package.name
                                        def version_base = js_package.version.tokenize(".")

                                        buildVersion = isMergeRequestBuild ? getBJVersion(version_base) + "${mr_version_postfix}" : getBJVersion(version_base)
                                        currentBuild.displayName = "${buildVersion}"
                                    }

                                    stage("Build Package") {

                                        project = js_package.name
                                        boolean containsData = project?.trim()

                                        if (!containsData) {
                                            error("Property 'name' in package.json cannot be found")
                                        }

                                        withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                                          variable: 'NEXUS_NPM_AUTH']]) {

                                            sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn version --no-git-tag-version --new-version ${buildVersion}"
                                            sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn install"
                                        }

                                        withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                                          variable: 'NEXUS_NPM_AUTH']]) {
                                            try {
                                                sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} JEST_JUNIT_OUTPUT=\"./unit-test-report.xml\" yarn test"
                                            } finally {
                                                junit 'unit-test-report.xml'
                                            }
                                        }

                                        withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                                          variable: 'NEXUS_NPM_AUTH']]) {
                                            sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn build"
                                        }
                                    }

                                    stage("Tag and Publish Package") {
                                        if (!isMergeRequestBuild) {
                                            withCredentials([usernamePassword(credentialsId: credentialId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                                                sh """
                                                git config user.name "${scmVars.GIT_AUTHOR_NAME}"
                                                git config user.email "${scmVars.GIT_AUTHOR_EMAIL}"
                                                git tag -am "By ${currentBuild.projectName}" v${buildVersion}
                                                git push https://${GIT_USERNAME}:${GIT_PASSWORD}@${scmVars.GIT_URL.substring(8)} v${
                                                    buildVersion
                                                }
                                            """
                                            }
                                        }

                                        withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                                          variable: 'NEXUS_NPM_AUTH']]) {
                                            sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} npm publish"
                                        }
                                    }

                                    stash name: "docker build input", includes: ".npmrc,Dockerfile"
                                }
                            }

                            stage('Build Docker Image') {
                                clientsK8sNode(clientsImage: 'stakater/pipeline-tools:1.16.0') {
                                    unstash "docker build input"
                                    echo 'NOTE: running pipelines for the first time will take longer as build and base docker images are pulled onto the node'
                                    if (!fileExists('Dockerfile')) {
                                        echo 'Creating dockerfile with onbuild base'
                                        writeFile file: 'Dockerfile', text: 'FROM node:5.3-onbuild'
                                    }

                                    container('clients') {
                                        newImageName = "${dockerUrl}/${project}:${buildVersion}"
                                        withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                                          variable: 'NEXUS_NPM_AUTH']]) {
                                            sh "docker build --build-arg NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} --network=host -t ${newImageName} ."
                                        }
                                        sh "docker push ${newImageName}"
                                    }

                                    rc = getDeploymentResourcesK8s {
                                        projectName = project
                                        port = 8080
                                        label = 'node'
                                        version = buildVersion
                                        imageName = newImageName
                                        dockerRegistrySecret = 'docker-registry-secret'
                                        readinessProbePath = "/readiness"
                                        livenessProbePath = "/health"
                                        ingressClass = "external-ingress"
                                        configMapToMount = configMap
                                    }
                                    writeFile file: "deployment.yaml", text: rc
                                    stash name: "manifest", includes: "deployment.yaml"
                                }
                            }

                            stage("Publish Docker Image") {
                                mavenNode(mavenImage: 'maven:3.5-jdk-8') {
                                    container(name: 'maven') {
                                        unstash "manifest"
                                        sh """
                                    mvn deploy:deploy-file \
                                        -Durl=https://${mavenRepo}/repository/maven-releases \
                                        -DrepositoryId=nexus \
                                        -DgroupId=com.scania.dd \
                                        -DartifactId=${project} \
                                        -Dversion=${buildVersion} \
                                        -Dpackaging=yml \
                                        -Dclassifier=kubernetes \
                                        -Dfile=deployment.yaml
                                """
                                        // TODO: Migrating Tools Cluster
                                        deployToAltRepo(secondaryMavenRepo)
                                    }
                                }
                            }
                        }

                        gitlabCommitStatus(name: "System test") {
                            systemtestStage([microservice: [name: project, version: buildVersion]], isMergeRequestBuild)
                        }

                        if (deployToDevAndProd) {
                            stage("Deploy to dev") {
                                build job: "${project}-dev-deploy", parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: buildVersion]]
                            }

                            stage("Deploy to prod") {
                                build job: "${project}-prod-deploy", parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: buildVersion]]
                            }
                        }
                    }
                }
            } finally {
                if (isMergeRequestBuild) {
                    deleteArtifactFromNexus(project, buildVersion, mavenRepo)
                    // TODO: Migrating Tools Cluster
                    deleteArtifactFromAlternateNexus(project, buildVersion, secondaryMavenRepo)
                }
            }
        }
    }
}

String getBJVersion(version_base) {

    int version_last = sh(
            script: "git tag | awk -F. 'BEGIN {print \"-1\"} /v${version_base[0]}.${version_base[1]}/{print \$3}' | sort -g  | tail -1",
            returnStdout: true
    ) as Integer
    return "${version_base[0]}.${version_base[1]}.${version_last + 1}"
}

// TODO: Migrating Tools Cluster
void deployToAltRepo(altMavenRepo) {
    try {
        sh """
            mvn deploy:deploy-file \
                -Durl=https://${altMavenRepo}/repository/maven-releases \
                -DrepositoryId=nexus \
                -DgroupId=com.scania.dd \
                -DartifactId=${serviceName} \
                -Dversion=${buildVersion} \
                -Dpackaging=yml \
                -Dclassifier=kubernetes \
                -Dfile=deployment.yaml
        """
    }
    catch (Exception ex) {
        println "WARNING: Deployment to alternate Nexus failed"
        println "Pipeline Will continue"
    }
}

// TODO: Migrating Tools Cluster
void deleteArtifactFromAlternateNexus(String artifact, String version, String nexusHost) {
    try {
        deleteArtifactFromNexus(project, buildVersion, nexusHost)
    }
    catch (Exception ex) {
        println "WARNING: Failed to delete artifact from alternate Nexus"
    }
}