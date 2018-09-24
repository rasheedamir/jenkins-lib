def call(Map parameters = [:], body) {
    def credentialId = 'dd_ci'

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    try {
        versionPrefix = VERSION_PREFIX
    } catch (Throwable ignored) {
        versionPrefix = "1.4"
    }
    def buildVersion
    def scmVars
    def newImageName

    def kubeConfig = params.KUBE_CONFIG
    def nameSpace = params.NAMESPACE
    def mavenRepo = params.MAVEN_REPO
    def dockerRepo = params.DOCKER_URL
    def serviceName = parameters.get('serviceName')
    assert serviceName != null: "Build fails because serviceName missing"

    timestamps {
        withSlackNotificatons() {

            podTemplate(envVars: [envVar(key: 'FABRIC8_DOCKER_REGISTRY_SERVICE_HOST', value: dockerRepo),
                                  envVar(key: 'FABRIC8_DOCKER_REGISTRY_SERVICE_PORT', value: '443')],
                    volumes: [
                            secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube'),
                    ])
                    {
                        clientsK8sNode(clientsImage: 'stakater/pipeline-tools:1.11.0') {
                            stage("Checkout") {
                                scmVars = checkout scm
                                int version_last = sh(
                                        script: "git tag | awk -F. 'BEGIN {print \"-1\"} /v${versionPrefix}/{print \$3}' | sort -g  | tail -1",
                                        returnStdout: true
                                )
                                buildVersion = "${versionPrefix}.${version_last + 1}"
                                currentBuild.displayName = "${buildVersion}"
                            }

                            stage('Build Release') {
                                echo 'NOTE: running pipelines for the first time will take longer as build and base docker images are pulled onto the node'

                                container('clients') {
                                    newImageName = "${dockerRepo}/${serviceName}:${buildVersion}"
                                    sh "docker build --network=host -t ${newImageName} ."
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
                                    sh "docker push ${newImageName}"
                                }

                                rc = getDeploymentResourcesK8s {
                                    projectName = serviceName
                                    replicas = 1
                                    port = 8080
                                    label = 'node'
                                    version = buildVersion
                                    imageName = newImageName
                                    dockerRegistrySecret = 'docker-registry-secret'
                                    readinessProbePath = "/readiness"
                                    livenessProbePath = "/health"
                                    ingressClass = "external-ingress"
                                    resourceRequestCPU = "20m"
                                    resourceRequestMemory = "500Mi"
                                    resourceLimitCPU = "100m"
                                    resourceLimitMemory = "500Mi"
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
                                    -Dversion=${buildVersion} \
                                    -Dpackaging=yml \
                                    -Dclassifier=kubernetes \
                                    -Dfile=deployment.yaml
                                """
                                        stash name: "manifest", includes: "deployment.yaml"
                                    }
                                }
                            }

                            stage("Deploy") {
                                echo "Deploying project ${serviceName} image version: ${buildVersion} yaml version: ${buildVersion}"
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
}
