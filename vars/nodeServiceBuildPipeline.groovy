def call() {

    def kubeConfig = params.KUBE_CONFIG
    def dockerUrl = params.DOCKER_URL
    def mavenRepo = params.MAVEN_REPO
    def credentialId = 'dd_ci'

    try {
        versionPrefix = VERSION_PREFIX
    } catch (Throwable ignored) {
        versionPrefix = "1.4"
    }

    def newVersion
    def scmVars
    def newImageName
    def serviceName

    podTemplate(envVars: [envVar(key: 'FABRIC8_DOCKER_REGISTRY_SERVICE_HOST', value: dockerUrl),
                          envVar(key: 'FABRIC8_DOCKER_REGISTRY_SERVICE_PORT', value: '443')],
            volumes: [
                    secretVolume(secretName: "${kubeConfig}", mountPath: '/home/jenkins/.kube'),
                    secretVolume(secretName: 'digitaldealer-service-secret', mountPath: '/etc/secrets/service-secret')
            ])
            {
                clientsK8sNode(clientsImage: 'stakater/docker-with-git:17.10') {

                    stage("Checkout") {
                        scmVars = checkout scm

                        def js_package = readJSON file: 'package.json'
                        serviceName = js_package.name
                        boolean containsData = serviceName?.trim()

                        if (!containsData) {
                            error("Property 'name' in package.json cannot be found")
                        }

                        int version_last = sh(
                                script: "git tag | awk -F. 'BEGIN {print \"-1\"} /v${versionPrefix}/{print \$3}' | sort -g  | tail -1",
                                returnStdout: true
                        )
                        newVersion = "${versionPrefix}.${version_last + 1}"
                        currentBuild.displayName = "${newVersion}"
                    }

                    stage('Build Release') {
                        echo 'NOTE: running pipelines for the first time will take longer as build and base docker images are pulled onto the node'
                        if (!fileExists ('Dockerfile')) {
                            writeFile file: 'Dockerfile', text: 'FROM node:5.3-onbuild'
                        }

                        container('clients') {
                            newImageName = "${dockerUrl}/${serviceName}:${newVersion}"
                            sh "docker build --network=host -t ${newImageName} ."
                            withCredentials([usernamePassword(credentialsId: credentialId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                            sh """
                                git config user.name "${scmVars.GIT_AUTHOR_NAME}"
                                git config user.email "${scmVars.GIT_AUTHOR_EMAIL}"
                                git tag -am "By ${currentBuild.projectName}" v${newVersion}
                                git push https://${GIT_USERNAME}:${GIT_PASSWORD}@${scmVars.GIT_URL.substring(8)} v${newVersion}
                            """
                            }
                            sh "docker push ${newImageName}"
                        }

                        rc = getDeploymentResourcesK8s {
                            projectName = serviceName
                            port = 8080
                            label = 'node'
                            version = newVersion
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
                                        -Dversion=${newVersion} \
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
                            echo "Deploying project ${serviceName} version: ${newVersion}"
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