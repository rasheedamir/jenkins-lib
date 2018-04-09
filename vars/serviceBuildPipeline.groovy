def call(body) {

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def versionPrefix = config.VERSION_PREFIX ?: "1.3"

    def buildVersion = "${versionPrefix}.${env.BUILD_NUMBER}"

    def kubeConfig = params.KUBE_CONFIG
    def nameSpace = params.NAMESPACE
    def mavenRepo = params.MAVEN_REPO
    def dockerRepo = params.DOCKER_URL
    def project
    def lock = ""

    currentBuild.displayName = "${buildVersion}"

    podTemplate(name: 'sa-secret',
            serviceAccount: 'digitaldealer-serviceaccount',
            envVars: [envVar(key: 'KUBERNETES_MASTER', value: 'https://kubernetes.default:443')],
            volumes: [
                    persistentVolumeClaim(claimName: 'jenkins-m2-cache', mountPath: '/root/.mvnrepository'),
                    secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube'),
                    secretVolume(secretName: 'digitaldealer-service-secret', mountPath: '/etc/secrets/service-secret')
            ])
            {

                mavenNode(mavenImage: 'maven:3.5-jdk-8') {
                    container(name: 'maven') {

                        stage("checkout") {
                            checkout scm
                            def pom = readMavenPom file: 'pom.xml'
                            project = pom.artifactId
                        }

                        stage('build') {
                            sh "git checkout -b ${env.JOB_NAME}-${buildVersion}"
                            sh "mvn org.codehaus.mojo:versions-maven-plugin:2.2:set -U -DnewVersion=${buildVersion}"
                            sh "mvn deploy"
                        }

                        stage('push docker image') {
                            sh "mvn fabric8:push -Ddocker.push.registry=${dockerRepo}"
                        }

                    }
                }

                def prevVersion = ""
                clientsNode {
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
                                if(errorMessage.contains("(NotFound)")){
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
                    clientsNode {

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
                        mavenNode(mavenImage: 'stakater/chrome:chrome-65') {
                            container(name: 'maven') {
                                try {
                                    stage("checking out mock tests") {
                                        git url: 'https://gitlab.com/digitaldealer/systemtest2.git',
                                                credentialsId: 'dd_ci',
                                                branch: 'master'
                                    }

                                    stage("running mock tests") {
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
                } catch (err) {
                    if (prevVersion != "") {
                        clientsNode {
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


                clientsNode {

                    stage("Deploy to dev") {
                        echo "Deploying project ${project} version: ${buildVersion}"
                        container(name: 'clients') {
                            unstash "manifest"
                            sh "kubectl apply  -n=${nameSpace} -f service-deployment.yaml"
                            sh "kubectl rollout status deployment/${project} -n=${nameSpace} --watch=true"
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

