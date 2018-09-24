def call(body) {

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def kubeConfig = params.KUBE_CONFIG
    def nameSpace = params.NAMESPACE

    timestamps {
        podTemplate(name: 'sa-secret',
                envVars: [envVar(key: 'KUBERNETES_MASTER', value: 'https://kubernetes.default:443')],
                volumes: [
                        secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube')
                ])
                {

                    clientsK8sNode(clientsImage: 'stakater/pipeline-tools:1.11.0') {

                        stage("checkout") {
                            checkout scm
                        }

                        stage("applying properties") {
                            container(name: 'clients') {
                                sh """
                                retVal=0;
                                for yaml in \$(find . -name '*.yaml'); do
                                  kubectl apply -n=${nameSpace} -f \$yaml;
                                  retVal=\$((\$retVal + \$?));
                                done;
                                [ \$retVal -eq 0 ]
                            """
                            }
                        }
                    }
                }
    }
}


