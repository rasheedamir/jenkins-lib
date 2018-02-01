def call(body) {

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    try {
        versionPrefix = VERSION_PREFIX
    } catch (Throwable ignored) {
        versionPrefix = "1.2"
    }

    def buildVersion = "${versionPrefix}.${env.BUILD_NUMBER}"

    def kubeConfig = params.KUBE_CONFIG
    def nameSpace = params.NAMESPACE
    def project = env.POM_ARTIFACTID

    podTemplate(name: 'sa-secret',
            serviceAccount: 'digitaldealer-serviceaccount',
            envVars: [envVar(key: 'KUBERNETES_MASTER', value: 'https://kubernetes.default:443')],
            volumes: [
                    secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube'),
                    secretVolume(secretName: 'digitaldealer-service-secret', mountPath: '/etc/secrets/service-secret')
            ])
            {

                mavenNode(mavenImage: 'maven:3.5-jdk-8') {
                    container(name: 'maven') {

                        stage("checkout") {
                            checkout scm
                        }

                        stage('Canary Release') {
                            mavenCanaryRelease {
                                version = buildVersion
                            }
                        }

                    }
                }

                clientsNode {

                    stage("Download manifest") {

                        echo "Fetching project ${project} version: ${buildVersion}"

                        withCredentials([string(credentialsId: 'nexus', variable: 'PWD')]) {
                            sh 'env'
                            sh "wget https://ddadmin:${PWD}@nexus.tools.tools178.digitaldealer.devtest.aws.scania.com/repository/maven-releases/com/scania/dd/${project}/${buildVersion}/${project}-${buildVersion}-kubernetes.yml -O /home/jenkins/service-deployment.yaml"
                        }
                    }

                    stage("Deploy") {
                        echo "Deploying project ${project} version: ${buildVersion}"
                        container(name: 'clients') {
                            sh "kubectl apply  -n=${nameSpace} -f /home/jenkins/service-deployment.yaml"
                            sh "kubectl rollout status deployment/${project} -n=${nameSpace}"
                        }
                    }
                }
            }
}


