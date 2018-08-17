def call(configMap) {

    def kubeConfig = params.KUBE_CONFIG
    def dockerUrl = params.DOCKER_URL
    def mavenRepo = params.MAVEN_REPO
    def credentialId = 'dd_ci'

    def buildVersion
    def scmVars
    def newImageName
    def project

    podTemplate(volumes: [secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube')]) {
        dockerNode(dockerImage: 'stakater/frontend-tools:latest') {
            container(name: 'docker') {
                stage("Build Package") {
                    scmVars = checkout scm

                    def js_package = readJSON file: 'package.json'
                    project = js_package.name
                    boolean containsData = project?.trim()

                    if (!containsData) {
                        error("Property 'name' in package.json cannot be found")
                    }

                    def version_base = js_package.version.tokenize(".")
                    int version_last = sh(
                            script: "git tag | awk -F. 'BEGIN {print \"-1\"} /v${version_base[0]}.${version_base[1]}/{print \$3}' | sort -g  | tail -1",
                            returnStdout: true
                    )
                    buildVersion = "${version_base[0]}.${version_base[1]}.${version_last + 1}"
                    currentBuild.displayName = "${buildVersion}"

                    withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                      variable: 'NEXUS_NPM_AUTH']]) {
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn version --no-git-tag-version --new-version ${buildVersion}"
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn install"
                    }

                    withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                      variable: 'NEXUS_NPM_AUTH']]) {
                        try {
                            sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} JEST_JUNIT_OUTPUT=\"./unit-test-report.xml\" yarn test"
                        } finally {
                            junit 'unit-test-report.xml'
                        }
                    }

                    withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                      variable: 'NEXUS_NPM_AUTH']]) {
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn build"
                    }
                }

                stage("Tag and Publish Package") {
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

                    withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                      variable: 'NEXUS_NPM_AUTH']]) {
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} npm publish"
                    }
                }

                stash name: "docker build input", includes: ".npmrc,Dockerfile"
            }
        }

        stage('Build Docker Image') {
            clientsK8sNode(clientsImage: 'stakater/pipeline-tools:1.11.0') {
                unstash "docker build input"
                echo 'NOTE: running pipelines for the first time will take longer as build and base docker images are pulled onto the node'
                if (!fileExists('Dockerfile')) {
                    echo 'Creating dockerfile with onbuild base'
                    writeFile file: 'Dockerfile', text: 'FROM node:5.3-onbuild'
                }

                container('clients') {
                    newImageName = "${dockerUrl}/${project}:${buildVersion}"
                    withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
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
                }
            }
        }

        String lockName = "${env.JOB_NAME}-${env.BUILD_NUMBER}"

        withLockOnMockEnvironment(lockName: lockName) {
            stage("System test") {
                def prevVersion = ""
                clientsK8sNode(clientsImage: 'stakater/pipeline-tools:1.11.0') {
                    container(name: 'clients') {
                        try {
                            prevVersion = sh(script: "kubectl -n=mock get service/${project} -o jsonpath='{.metadata.labels.version}' 2>/tmp/serr.txt", returnStdout: true).toString().trim()
                            echo "Old version is: ${prevVersion}"
                        } catch (err) {
                            echo "Reading old version failed: $err"
                            def errorMessage = readFile "/tmp/serr.txt"
                            echo "Message: $errorMessage"
                            if (errorMessage.contains("(NotFound)")) {
                                echo "Probably this is the first deployment"
                            } else {
                                echo "We did not expect this!"
                                throw err
                            }
                        }
                    }

                    try {
                        build job: "${project}-mock-deploy", parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: buildVersion]]
                        timeout(20) {
                            mavenNode(mavenImage: 'stakater/chrome:67') {
                                container(name: 'maven') {
                                    try {
                                        git url: 'https://gitlab.com/digitaldealer/systemtest2.git',
                                                credentialsId: 'dd_ci',
                                                branch: 'master'

                                        sh 'chmod +x mvnw'
                                        sh './mvnw clean test -Dbrowser=chrome -Dheadless=true -DsuiteXmlFile=smoketest-mock.xml'
                                    } finally {
                                        zip zipFile: 'output.zip', dir: 'target', archive: true
                                        archiveArtifacts artifacts: 'target/screenshots/*', allowEmptyArchive: true
                                        junit 'target/surefire-reports/*.xml'
                                    }
                                }
                            }
                        }
                    } catch (err) {
                        if (prevVersion != "") {
                            // Rolling back only rolls back the deployment, the service stays
                            // If we reapply an old manifest, the service will be correct, the replica set will be reused, but the deployment/replica set will have a new revision
                            build job: "${project}-dev-deploy", parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: prevVersion]]
                        } else {
                            echo "There were test failures, but there was no previous version, not rolling back mock"
                        }
                        throw err
                    }
                }
            }
        }

        stage("Deploy") {
            build job: "${project}-dev-deploy", parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: buildVersion]]
            build job: "${project}-prod-deploy", parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: buildVersion]]
        }
    }
}
