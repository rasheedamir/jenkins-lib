def call() {
    def credentialId = 'dd_ci'

    def buildVersion
    def scmVars

    podTemplate(
            volumes: [
                    persistentVolumeClaim(claimName: 'jenkins-m2-cache', mountPath: '/root/.mvnrepository')
            ]) {
        mavenNode(mavenImage: 'maven:3.5-jdk-8') {
            container(name: 'maven') {

                stage("checkout") {
                    scmVars = checkout scm
                    def pom = readMavenPom file: 'pom.xml'
                    def version_base = pom.version.tokenize(".")
                    def versionPrefix = "${version_base[0]}.${version_base[1]}"
                    int version_last = sh(
                            script: "git tag | awk -F. 'BEGIN {print \"-1\"} /v${versionPrefix}/{print \$3}' | sort -g  | tail -1",
                            returnStdout: true
                    )
                    buildVersion = "${versionPrefix}.${version_last + 1}"
                    currentBuild.displayName = "${buildVersion}"
                }

                stage('build') {
                    sh "mvn org.codehaus.mojo:versions-maven-plugin:2.2:set -U -DnewVersion=${buildVersion}"
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
                    sh "mvn clean deploy"
                }
            }
        }
    }
}

