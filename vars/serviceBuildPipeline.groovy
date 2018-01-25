
def call(body) {

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    try {
        versionPrefix = VERSION_PREFIX
    } catch (Throwable e) {
        versionPrefix = "1.0"
    }

    def canaryVersion = "${versionPrefix}.${env.BUILD_NUMBER}"
    def label = "buildpod.${env.JOB_NAME}.${env.BUILD_NUMBER}".replace('-', '_').replace('/', '_')

    def project = currentBuild.projectName.tokenize( '-' )[0]
    def stashName = ""

    podTemplate(name: 'sa-secret',
            serviceAccount: 'digitaldealer-serviceaccount',
            envVars: [envVar(key: 'KUBERNETES_MASTER', value: 'https://kubernetes.default:443')],
            volumes: [
                    secretVolume(secretName: 'digitaldealer-service-secret', mountPath: '/etc/secrets/service-secret'),
                    secretVolume(secretName: "${KUBE_CONFIG}", mountPath: '/home/jenkins/.kube')
                ])
            {

                mavenNode(mavenImage: 'maven:3.5-jdk-8') {
                    container(name: 'maven') {

                        stage("checkout") {
                            checkout scm
                        }

                        stage('Canary Release') {
                            mavenCanaryRelease {
                                version = canaryVersion
                            }
                        }
                    }
                }

                clientsNode {

                    stage("Download manifest") {

                        echo "Fetching project ${project} version: ${canaryVersion}"

                        withCredentials([string(credentialsId: 'nexus', variable: 'PWD')]) {
                            sh "wget http://ddadmin:${PWD}@nexus/repository/maven-releases/com/scania/dd/${project}/${canaryVersion}/${project}-${canaryVersion}-kubernetes.yml -O /home/jenkins/service-deployment.yaml"
                        }
                    }

                    stage("Deploy") {
                        echo "Deploying project ${project} version: ${canaryVersion}"
                        container(name: 'clients') {
                            sh "kubectl apply  -n=${NAMESPACE} -f /home/jenkins/service-deployment.yaml"
                        }
                    }
                }
            }
}
