
def call(body) {

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    try {
        versionPrefix = VERSION_PREFIX
    } catch (Throwable e) {
        versionPrefix = "1.2"
    }

    def canaryVersion = "${versionPrefix}.${env.BUILD_NUMBER}"
    def label = "buildpod.${env.JOB_NAME}.${env.BUILD_NUMBER}".replace('-', '_').replace('/', '_')


    def stashName = ""

    podTemplate(name: 'sa-secret',
            serviceAccount: 'digitaldealer-serviceaccount',
            envVars: [envVar(key: 'KUBERNETES_MASTER', value: 'https://kubernetes.default:443')],
            volumes: [secretVolume(secretName: 'digitaldealer-service-secret', mountPath: '/etc/secrets/service-secret')])
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

                        stage('Rollout to Stage') {
                            kubernetesApply(registry: DOCKER_URL, environment: NAMESPACE)
                            stashName = label
                            stash includes: '**/*.yml', name: stashName
                        }
                    }
                }
            }
}
