def call(Map parameters = [:], body) {

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    try {
        versionPrefix = VERSION_PREFIX
    } catch (Throwable ignored) {
        versionPrefix = "1.2"
    }
    def ymlVersion = "${versionPrefix}.${env.BUILD_NUMBER}"

    def kubeConfig = params.KUBE_CONFIG
    def nameSpace = params.NAMESPACE
    def mavenRepo = params.MAVEN_REPO
    def dockerRepo = params.DOCKER_URL
    def serviceName = parameters.get('serviceName')
    assert serviceName != null : "Build fails because serviceName missing"

    podTemplate(envVars: [envVar(key: 'FABRIC8_DOCKER_REGISTRY_SERVICE_HOST', value: dockerRepo),
                          envVar(key: 'FABRIC8_DOCKER_REGISTRY_SERVICE_PORT', value: '443')],
            volumes: [
                    secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube'),
            ])
            {
                clientsK8sNode(clientsImage: 'stakater/docker-with-git:17.10') {
                    def newImageVersion = ''
                    def rc = ""

                    def newImageName

                    checkout scm

                    stage('Build Release') {
                        echo 'NOTE: running pipelines for the first time will take longer as build and base docker images are pulled onto the node'

                        container('clients') {
                            newImageVersion = "v" + sh(script: 'git rev-parse --short HEAD', returnStdout: true).toString().trim()
                            newImageName = "${dockerRepo}/${serviceName}:${newImageVersion}"
                            sh "docker build -t ${newImageName} ."
                            sh "docker push ${newImageName}"
                        }

                        rc = getDeploymentResourcesK8s {
                            projectName = serviceName
                            port = 8080
                            label = 'node'
                            version = newImageVersion
                            imageName = newImageName
                            dockerRegistrySecret = 'docker-registry-secret'
                            readinessProbePath = "/readiness"
                            livenessProbePath = "/health"
                            ingressClass = "external-ingress"
                        }

                        writeFile file: "deployment.yaml", text: rc

                        stash name: "manifest", includes: "deployment.yaml"
                    }

                    mavenNode(mavenImage: 'maven:3.5-jdk-8') {
                        container(name: 'maven') {
                            stage("Upload to nexus") {
                                unstash "manifest"
                                sh """
                                mvn deploy:deploy-file \
                                    -Durl=https://${mavenRepo}/repository/maven-releases \
                                    -DrepositoryId=nexus \
                                    -DgroupId=com.scania.dd \
                                    -DartifactId=${serviceName} \
                                    -Dversion=${ymlVersion} \
                                    -Dpackaging=yml \
                                    -Dclassifier=kubernetes \
                                    -Dfile=deployment.yaml
                                """
                                stash name: "manifest", includes: "deployment.yaml"
                            }
                        }
                    }

                    clientsNode {
                        stage("Deploy") {
                            echo "Deploying project ${serviceName} image version: ${newImageVersion} yaml version: ${ymlVersion}"
                            unstash "manifest"
                            container(name: 'clients') {
                                sh "kubectl apply  -n=${nameSpace} -f deployment.yaml"
                                sh "kubectl rollout status deployment/${serviceName} -n=${nameSpace}"
                            }
                        }
                    }


                }
            }
}
