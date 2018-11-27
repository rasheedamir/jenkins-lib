def call(body) {
    def credentialId = 'dd_ci'

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def kubeConfig = params.KUBE_CONFIG
    def dockerRepo = params.DOCKER_URL
    def nexusHost = params.MAVEN_REPO
    def isMergeRequestBuild = params.IS_MERGE_REQUEST_BUILD ?: false
    echo "isMergeRequestBuild: ${isMergeRequestBuild}"

    assert !(isMergeRequestBuild && env.gitlabSourceBranch == null)

    def onlyMock = config.onlyMock ?: false

    def deployToDevAndProd = !(isMergeRequestBuild || onlyMock)
    echo "deployToDevAndProd: ${deployToDevAndProd}"

    def project
    def buildVersion
    def scmVars

    timestamps {
        withSlackNotificatons() {
            try {
                gitlabBuilds(builds: ["Build", "System test"]) {
                    podTemplate(
                            name: 'sa-secret',
                            serviceAccount: 'digitaldealer-serviceaccount',
                            envVars: [envVar(key: 'KUBERNETES_MASTER', value: 'https://kubernetes.default:443')],
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
                                    mavenImage: 'stakater/maven-jenkins:3.5.4-0.6',
                                    javaOptions: '-Duser.home=/home/jenkins -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -Dsun.zip.disableMemoryMapping=true -XX:+UseParallelGC -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=90',
                                    mavenOpts: '-Duser.home=/home/jenkins -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn') {

                                container(name: 'maven') {

                                    stage("checkout") {
                                        scmVars = checkout scm
                                        def pom = readMavenPom file: 'pom.xml'
                                        project = pom.artifactId

                                        if (isMergeRequestBuild) {
                                            def branchName = env.gitlabSourceBranch
                                            buildVersion = "${branchName}-${currentBuild.number}"
                                        } else {
                                            def versionPrefix = config.VERSION_PREFIX ?: "1.4"
                                            int version_last = sh(
                                                    script: "git tag | awk -F. 'BEGIN {print \"-1\"} /v${versionPrefix}/{print \$3}' | sort -g  | tail -1",
                                                    returnStdout: true
                                            )
                                            buildVersion = "${versionPrefix}.${version_last + 1}"
                                        }

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
                                    }

                                    stage('push docker image') {
                                        sh "mvn fabric8:push -Ddocker.push.registry=${dockerRepo}"
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
                }
            }
        }
    }
}
