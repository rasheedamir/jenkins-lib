def call(body) {
    def credentialId = 'dd_ci'

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def kubeConfig = params.KUBE_CONFIG
    def nameSpace = params.NAMESPACE
    def mavenRepo = params.MAVEN_REPO
    def dockerRepo = params.DOCKER_URL
    def project
    def lock = ""
    def buildVersion
    def scmVars
    def onlyMock = config.onlyMock ?: false
    def soapITJobName = 'soap-integration-tests'

    withSlackNotificatons() {

        podTemplate(name: 'sa-secret',
                serviceAccount: 'digitaldealer-serviceaccount',
                envVars: [envVar(key: 'KUBERNETES_MASTER', value: 'https://kubernetes.default:443')],
                volumes: [
                        secretVolume(secretName: 'jenkins-maven-settings', mountPath: '/home/jenkins/.m2'),
                        persistentVolumeClaim(claimName: 'jenkins-m2-cache', mountPath: '/home/jenkins/.mvnrepository'),
                        secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube'),
                        secretVolume(secretName: 'digitaldealer-service-secret', mountPath: '/etc/secrets/service-secret')
                ])
                {
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

                    mavenNode(mavenImage: 'stakater/maven-jenkins:3.5.4-0.6',
                             javaOptions: '-Duser.home=/home/jenkins -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -Dsun.zip.disableMemoryMapping=true -XX:+UseParallelGC -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=90',
                             mavenOpts: '-Duser.home=/home/jenkins -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn') {
                        container(name: 'maven') {

                            stage("checkout") {
                                scmVars = checkout scm
                                def pom = readMavenPom file: 'pom.xml'
                                project = pom.artifactId
                                def versionPrefix = config.VERSION_PREFIX ?: "1.4"
                                int version_last = sh(
                                        script: "git tag | awk -F. 'BEGIN {print \"-1\"} /v${versionPrefix}/{print \$3}' | sort -g  | tail -1",
                                        returnStdout: true
                                )
                                buildVersion = "${versionPrefix}.${version_last + 1}"
                                currentBuild.displayName = "${buildVersion}"
                            }

                            stage('build') {
                                sh "git checkout -b ${env.JOB_NAME}-${buildVersion}"
                                sh "mvn org.codehaus.mojo:versions-maven-plugin:2.2:set -U -DnewVersion=${buildVersion}"
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
                                sh "mvn deploy"
                            }

                            stage('push docker image') {
                                sh "mvn fabric8:push -Ddocker.push.registry=${dockerRepo}"
                            }

                        }
                    }

                    def prevVersion = ""
                    clientsK8sNode(clientsImage: 'stakater/pipeline-tools:1.11.0') {
                        stage("Download manifest") {
                            echo "Fetching project ${project} version: ${buildVersion} for testing"
                            withCredentials([string(credentialsId: 'nexus', variable: 'PWD')]) {
                                sh "wget https://ddadmin:${PWD}@${mavenRepo}/repository/maven-releases/com/scania/dd/${project}/${buildVersion}/${project}-${buildVersion}-kubernetes.yml -O service-deployment.yaml"
                            }
                            stash includes: 'service-deployment.yaml', name: 'manifest'

                            container(name: 'clients') {
                                echo "Save prev version"
                                try {
                                    prevVersion = sh(script: "kubectl -n=mock get service/${project} -o jsonpath='{.metadata.labels.version}' 2>${env.WORKSPACE}/serr.txt", returnStdout: true).toString().trim()
                                    echo "Old version is: ${prevVersion}"
                                } catch (err) {
                                    echo "Reading old version failed: $err"
                                    def errorMessage = readFile "${env.WORKSPACE}/serr.txt"
                                    echo "Message: $errorMessage"
                                    if (errorMessage.contains("(NotFound)")) {
                                        echo "Probably this is the first deployment"
                                    } else {
                                        echo "We did not expect this!"
                                        throw err
                                    }
                                }
                            }
                            if (prevVersion != "") {
                                echo "Fetching project ${project} version: ${prevVersion} for rollback"
                                withCredentials([string(credentialsId: 'nexus', variable: 'PWD')]) {
                                    sh "wget https://ddadmin:${PWD}@${mavenRepo}/repository/maven-releases/com/scania/dd/${project}/${prevVersion}/${project}-${prevVersion}-kubernetes.yml -O old-service-deployment.yaml"
                                }
                                stash includes: 'old-service-deployment.yaml', name: 'old-manifest'
                            } else {
                                echo "Not fetching manifest for rollback, as there is no previous deployed version"
                            }
                        }
                    }

                    stage("Acquire lock on mock") {
                        lock = tryLock("mock", env.JOB_NAME, (20 + 5) * 60, 4, buildVersion)
                        while (lock == "") {
                            echo "Waiting for lock"
                            sleep 4
                            lock = tryLock("mock", env.JOB_NAME, (20 + 5) * 60, 4, buildVersion)
                        }
                    }

                    try {
                        clientsK8sNode(clientsImage: 'stakater/pipeline-tools:1.11.0') {

                            stage("Deploy to mock") {

                                echo "Deploying project ${project} version: ${buildVersion}"
                                container(name: 'clients') {
                                    unstash "manifest"
                                    sh "kubectl apply  -n=mock -f service-deployment.yaml"
                                    sh "kubectl rollout status deployment/${project} -n=mock --watch=true"
                                }
                            }
                        }

                        timeout(20) {
                            mavenNode(mavenImage: 'stakater/chrome:67') {
                                container(name: 'maven') {

                                    parallel SoapIntegrationTests: {
                                        stage('Run soap integration tests') {
                                            echo "Running soap integration tests on mock"
                                            build job: "${soapITJobName}"
                                        }
                                    },
                                    SystemTests: {
                                        try {
                                            stage("Run mock tests") {
                                                git url: 'https://gitlab.com/digitaldealer/systemtest2.git',
                                                        credentialsId: 'dd_ci',
                                                        branch: 'master'

                                                sh 'chmod +x mvnw'
                                                sh './mvnw clean test -Dbrowser=chrome -Dheadless=true -DsuiteXmlFile=smoketest-mock.xml'
                                            }
                                        } finally {
                                            zip zipFile: 'output.zip', dir: 'target', archive: true
                                            archiveArtifacts artifacts: 'target/screenshots/*', allowEmptyArchive: true
                                            junit 'target/surefire-reports/*.xml'
                                        }
                                    }
                                }
                            }
                        }
                    } catch (err) {
                        if (prevVersion != "") {
                            clientsK8sNode(clientsImage: 'stakater/pipeline-tools:1.11.0') {
                                echo "There were test failures. Rolling back mock"
                                container(name: 'clients') {
//                            Rolling back only rolls back the deployment, the service stays:
//                            sh "kubectl rollout undo deployment/${project} -n=mock"
//                            If we reapply an old manifest, the service will be correct, the replica set will be reused, but the deployment/replica set will have a new revision
                                    unstash "old-manifest"
                                    sh "kubectl apply  -n=mock -f old-service-deployment.yaml"
                                    sh "kubectl rollout status deployment/${project} -n=mock --watch=true"
                                }
                            }
                        } else {
                            echo "There were test failures, but there was no previous version, not rolling back mock"
                        }
                        throw err
                    } finally {
                        releaseLock(lock)
                    }

                    if (onlyMock) {
                        echo "onlyMock flag enabled, skipping deployment to dev and prod"
                    } else {
                        clientsK8sNode(clientsImage: 'stakater/pipeline-tools:1.11.0') {

                            stage("Deploy to dev") {
                                echo "Deploying project ${project} version: ${buildVersion}"
                                container(name: 'clients') {
                                    unstash "manifest"
                                    sh "kubectl apply  -n=${nameSpace} -f service-deployment.yaml"
                                    sh "kubectl rollout status deployment/${project} -n=${nameSpace} --watch=true"
                                }
                            }

                            stage('Deploy to prod') {
                                build job: "${project}-prod-deploy", parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: buildVersion]]
                            }
                        }
                    }
                }
    }
}

private String tryLock(lock, job_name, activeWait, lockWait, buildVersion) {
    def url = new URL("http://restful-distributed-lock-manager.tools:8080/locks/${lock}")
    def conn = url.openConnection()
    conn.setDoOutput(true)
    def writer = new OutputStreamWriter(conn.getOutputStream())
    writer.write("{\"title\": \"${job_name}-${buildVersion}\", \"lifetime\": ${activeWait}, \"wait\": ${lockWait}}")
    writer.flush()
    writer.close()

    def responseCode = conn.getResponseCode()
    if (responseCode == 201) {
        def lockUrl = conn.getHeaderField("Location")
        echo "Acquired ${lockUrl}"
        return lockUrl
    } else if (responseCode != 408) {
        echo "Something went wrong when locking: ${responseCode}"
    }
    echo "Did not get a lock"
    return ""
}

private void releaseLock(lockUrl) {
    echo "Releasing ${lockUrl}"
    def url = new URL(lockUrl)
    def conn = url.openConnection()
    conn.setRequestMethod("DELETE")
    def responseCode = conn.getResponseCode()
    if (responseCode != 204) {
        echo "Something went wrong when releaseing the lock: ${responseCode}"
    }
}

