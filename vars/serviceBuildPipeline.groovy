def call(body) {
    def credentialId = 'dd_ci'

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def sonarScanQueryInterval = config.sonarScanQueryInterval ?: 5000
    def sonarScanQueryMaxAttempts = config.sonarScanQueryMaxAttempts ?: 240
    def sourcesPath = config.sourcesPath ?: "src/main/java"
    def testsPath = config.testsPath ?: "src/test/java"
    def binariesPath = config.binariesPath ?: "target/classes"
    def junitReportsPath = config.junitReportsPath ?: "target/surefire-reports"
    def coverageReportsPath = config.coverageReportsPath ?: "target/jacoco.exec"

    def kubeConfig = params.KUBE_CONFIG
    def dockerRepo = params.DOCKER_URL
    def nexusHost = params.MAVEN_REPO
    def sonarQubeHost = params.SONARQUBE_HOST
    def isMergeRequestBuild = params.IS_MERGE_REQUEST_BUILD ?: false
    echo "isMergeRequestBuild: ${isMergeRequestBuild}"
    echo "checkoutBranch: ${env.gitlabBranch}"

    assert !(isMergeRequestBuild && env.gitlabSourceBranch == null)
    def branchName = isMergeRequestBuild ? env.gitlabSourceBranch : env.gitlabBranch ?: 'master'

    def onlyMock = config.onlyMock ?: false

    def deployToDevAndProd = !(isMergeRequestBuild || onlyMock)
    echo "deployToDevAndProd: ${deployToDevAndProd}"
    
    def project
    def buildVersion
    def scmVars
    def getVersion = isMergeRequestBuild ? { getMRVersion(branchName, currentBuild) } : { getBJVersion(config) }

    timestamps {
        withSlackNotificatons(isMergeRequestBuild: isMergeRequestBuild) {
            try {
                gitlabBuilds(builds: ["Build", "System test"]) {
                    podTemplate(
                            name: 'sa-secret',
                            serviceAccount: 'digitaldealer-serviceaccount',
                            envVars: [
                                envVar(key: 'KUBERNETES_MASTER', value: 'https://kubernetes.default:443'),
                                secretEnvVar(key: 'SONARQUBE_TOKEN', secretName: 'jenkins-sonarqube', secretKey: 'token')
                            ],
                            volumes: [
                                    secretVolume(secretName: 'jenkins-maven-settings', mountPath: '/home/jenkins/.m2'),
                                    persistentVolumeClaim(claimName: 'jenkins-m2-cache', mountPath: '/home/jenkins/.mvnrepository'),
                                    secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube'),
                                    secretVolume(secretName: 'digitaldealer-service-secret', mountPath: '/etc/secrets/service-secret')
                            ]) {

                        mavenNode(maven: 'stakater/pipeline-tools:1.11.0') {
                            container(name: 'maven') {
                                // Ensure "jenkins" user is the owner of mounted Maven repository
                                // As the default owner in the volume would be "root" unless changed once
                                stage("Change Ownership") {
                                    def mvnRepository = "/home/jenkins/.mvnrepository"
                                    // Run chown only if the directory is not already owned by the jenkins user
                                    sh """
                                    MVN_DIR_OWNER=\$(ls -ld ${mvnRepository} | awk '{print \$3}')
                                    if [ \${MVN_DIR_OWNER} != '10000' ];
                                    then
                                        chown 10000 -R /home/jenkins/.mvnrepository
                                    fi
                                """
                                }
                            }
                        }

                        gitlabCommitStatus(name: "Build") {
                            mavenNode(
                                    mavenImage: 'stakater/builder-maven:3.5.4-jdk1.8-v2.0.1-v0.0.5',
                                    javaOptions: '-Duser.home=/home/jenkins -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -Dsun.zip.disableMemoryMapping=true -XX:+UseParallelGC -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=90',
                                    mavenOpts: '-Duser.home=/home/jenkins -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn') {

                                container(name: 'maven') {

                                    stage("checkout") {
                                        scmVars = checkout([$class: 'GitSCM', branches: [[name: branchName]], userRemoteConfigs: scm.getUserRemoteConfigs()])
                                        def pom = readMavenPom file: 'pom.xml'
                                        // Explicitly set project name from property
                                        // NOTE: 'projectName' Should only be set for multi-module projects
                                        project = config.projectName ?: pom.artifactId
                                        buildVersion = getVersion()
                                        currentBuild.displayName = "${buildVersion}"
                                    }

                                    stage('build') {
                                        sh "git checkout -b ${env.JOB_NAME}-${buildVersion}"
                                        sh "mvn org.codehaus.mojo:versions-maven-plugin:2.2:set -U -DnewVersion=${buildVersion}"

                                        if (!isMergeRequestBuild) {
                                            withCredentials([usernamePassword(credentialsId: credentialId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                                                def gitUrl = scmVars.GIT_URL.substring(8)
                                                sh """
                                                git config user.name "${scmVars.GIT_AUTHOR_NAME}"
                                                git config user.email "${scmVars.GIT_AUTHOR_EMAIL}"
                                                git tag -am "By ${currentBuild.projectName}" v${buildVersion}
                                                git push https://${GIT_USERNAME}:${GIT_PASSWORD}@${gitUrl} v${
                                                    buildVersion
                                                }
                                            """
                                            }
                                        }

                                        sh "mvn deploy"
                                        //TODO: Migrating Tools Cluster
                                        deployToAltRepo()
                                    }

                                    stage('SonarQube Analysis') {
                                        sh """
                                            /bin/sonar-scanner \
                                                -Dsonar.host.url=${sonarQubeHost} \
                                                -Dsonar.login=\${SONARQUBE_TOKEN} \
                                                -Dsonar.projectKey=${project} \
                                                -Dsonar.projectVersion=${buildVersion} \
                                                -Dsonar.sources="${sourcesPath}" \
                                                -Dsonar.tests="${testsPath}" \
                                                -Dsonar.java.binaries="${binariesPath}" \
                                                -Dsonar.junit.reportPaths="${junitReportsPath}" \
                                                -Dsonar.jacoco.reportPaths="${coverageReportsPath}" \
                                                -Dsonar.buildbreaker.alternativeServerUrl=${sonarQubeHost} \
                                                -Dsonar.buildbreaker.queryInterval=${sonarScanQueryInterval} \
                                                -Dsonar.buildbreaker.queryMaxAttempts=${sonarScanQueryMaxAttempts}
                                        """
                                    }

                                    stage('push docker image') {
                                        sh "mvn fabric8:push -Ddocker.push.registry=${dockerRepo}"
                                        // TODO: Migrating Tools Cluster
                                        pushImageToAltRepo(project, buildVersion, "docker.lab.k8syard.com")
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

                            stage('Deploy to prod') {
                                build job: "${project}-prod-deploy", parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: buildVersion]]
                            }
                        }
                    }
                }
            }
            finally {
                if (isMergeRequestBuild) {
                    deleteArtifactFromNexus(project, buildVersion, nexusHost)
                    // TODO: Migrating Tools Cluster
                    deleteArtifactFromAlternateNexus(project, buildVersion, "nexus.lab.k8syard.com")
                }
            }
        }
    }
}

String getBJVersion(config) {
    def versionPrefix = config.VERSION_PREFIX ?: "1.4"
    int version_last = sh(
            script: "git tag | awk -F. 'BEGIN {print \"-1\"} /v${versionPrefix}/{print \$3}' | sort -g  | tail -1",
            returnStdout: true
    ) as Integer
    return "${versionPrefix}.${version_last + 1}"
}

static String getMRVersion(branchName, currentBuild) {
    def buildNumber = currentBuild.number
    return "${branchName}-${buildNumber}"
}

// TODO: Migrating Tools Cluster
void deployToAltRepo() {
    try {
        sh """
            echo "Deploy to Alternate Nexus"
            mvn deploy -Pnexus-v2 -Dmaven.test.skip=true -Dmaven.install.skip=true
        """
    }
    catch(Exception ex) {
        println "WARNING: Deployment to alternate Nexus failed"
        println "Pipeline Will continue"
    }
}

// TODO: Migrating Tools Cluster
void pushImageToAltRepo(project, buildVersion, altRepository) {
    try {
        sh """
            echo "Push to alternate Docker Repository"
            repositoryStatus=\$(curl -L -s -o /dev/null -w "%{http_code}" ${altRepository})
            if [ \${repositoryStatus} -eq "503" ]; then
                echo "Cannot Reach docker repository: Stauts 503"
                exit 1;
            fi
            docker tag dd/${project}:${buildVersion} ${altRepository}/dd/${project}:${buildVersion}
            docker push ${altRepository}/dd/${project}:${buildVersion}
        """
    }
    catch(Exception ex) {
        println "WARNING: Pushing docker image to alternate repository failed"
        println "Pipeline will continue."
    }
}

// TODO: Migrating Tools Cluster
void deleteArtifactFromAlternateNexus(String artifact, String version, String nexusHost) {
    try {
        deleteArtifactFromNexus(project, buildVersion, nexusHost)
    }
    catch(Exception ex) {
        println "WARNING: Failed to delete artifact from alternate Nexus"
    }
}