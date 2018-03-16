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

                    stage("Deploy to mock") {

                        echo "Deploying project ${project} version: ${buildVersion}"
                        container(name: 'clients') {
                            unstash "manifest"
                            sh "kubectl apply  -n=mock -f service-deployment.yaml"
                            sh "kubectl rollout status deployment/${project} -n=mock --watch=true"
                        }
                    }
                }

                timeout(10) {
                    mavenNode(mavenImage: 'stakater/chrome-headless') {
                        container(name: 'maven') {
                            try {

                                stage("running mock tests") {
                                    checkout scm
                                    sh 'chmod +x mvnw'
                                    sh './mvnw clean test -Dbrowser=chrome -Dheadless=true -DsuiteXmlFile=smoketest-mock.xml'
                                }

                            } catch (err) {
                                clientsNode {
                                    echo "There was test failures. Rolling back mock"
                                    container(name: 'clients') {
                                        unstash "manifest"
                                        sh "kubectl rollout undo  -n=mock -f /home/jenkins/service-deployment.yaml"
                                        sh "kubectl rollout status deployment/${project} -n=mock --watch=true"
                                    }
                                }
                                throw err
                            } finally {
                                zip zipFile: 'output.zip', dir: 'target', archive: true
                                archiveArtifacts artifacts: 'target/screenshots/*', allowEmptyArchive: true
                                junit 'target/surefire-reports/*.xml'
                            }
                        }
                    }
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


