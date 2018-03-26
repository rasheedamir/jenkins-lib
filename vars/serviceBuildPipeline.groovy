def call(body) {

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def versionPrefix = config.VERSION_PREFIX ?: "1.2"

    def buildVersion = "${versionPrefix}.${env.BUILD_NUMBER}"

    def kubeConfig = params.KUBE_CONFIG
    def nameSpace = params.NAMESPACE
    def mavenRepo = params.MAVEN_REPO
    def dockerRepo = params.DOCKER_URL
    def project
    def lock = ""

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

                clientsNode {

                    stage("Download manifest") {

                        echo "Fetching project ${project} version: ${buildVersion}"

                        withCredentials([string(credentialsId: 'nexus', variable: 'PWD')]) {
                            sh "wget https://ddadmin:${PWD}@${mavenRepo}/repository/maven-releases/com/scania/dd/${project}/${buildVersion}/${project}-${buildVersion}-kubernetes.yml -O service-deployment.yaml"
                        }
                        stash includes: 'service-deployment.yaml', name: 'manifest'
                    }
                }

                stage("Acquire lock on mock") {
                    lock = tryLock("mock", env.JOB_NAME, (20 + 5) * 60, 4, buildVersion)
                    while (lock == "") {
                        println "Waiting for lock"
                        sleep 4
                        loc = tryLock("mock", env.JOB_NAME, (20 + 5) * 60, 4, buildVersion)
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

                    timeout(1) {
                        mavenNode(mavenImage: 'stakater/chrome:chrome-65') {
                            container(name: 'maven') {

                                stage("checking out mock tests") {
                                    git url: 'https://gitlab.com/digitaldealer/systemtest2.git',
                                            credentialsId: 'dd_ci',
                                            branch: 'master'
                                }

                                stage("running mock tests") {
                                    sh 'chmod +x mvnw'
                                    sh './mvnw clean test -Dbrowser=chrome -Dheadless=true -DsuiteXmlFile=smoketest-mock.xml'
                                }


                            }
                        }
                    }
                } catch (err) {

                    clientsNode {
                        echo "There was test failures. Rolling back mock"
                        container(name: 'clients') {
                            sh "kubectl rollout undo deployment/${project} -n=mock"
                            sh "kubectl rollout status deployment/${project} -n=mock --watch=true"
                        }
                    }
                    throw err
                } finally {
                    releaseLock(lock)

                    zip zipFile: 'output.zip', dir: 'target', archive: true
                    archiveArtifacts artifacts: 'target/screenshots/*', allowEmptyArchive: true
                    junit 'target/surefire-reports/*.xml'
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
        return conn.getHeaderField("Location")
    } else if (responseCode != 408) {
        println "Something went wrong when locking: ${responseCode}"
    }
    return ""
}

private void releaseLock(lockUrl) {
    def url = new URL(lockUrl)
    def conn = url.openConnection()
    conn.setRequestMethod("DELETE")
    def responseCode = conn.getResponseCode()
    if (responseCode != 204) {
        println "Something went wrong when releaseing the lock: ${responseCode}"
    }
}

