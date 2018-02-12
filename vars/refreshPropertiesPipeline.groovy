def call(body) {

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def kubeConfig = params.KUBE_CONFIG
    def nameSpace = params.NAMESPACE

    podTemplate(name: 'sa-secret',
            envVars: [envVar(key: 'KUBERNETES_MASTER', value: 'https://kubernetes.default:443')],
            volumes: [
                    secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube')
            ])
            {

                clientsNode {

                    stage("checkout") {
                        checkout scm
                    }

                    stage("applying properties") {
                        container(name: 'clients') {
                            sh "find . -name '*.yaml' -exec kubectl apply  -n=${nameSpace} -f {} \\;"
                        }
                    }
                }
            }
}


