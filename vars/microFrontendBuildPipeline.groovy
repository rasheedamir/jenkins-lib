def call(body) {
    def credentialId = 'dd_ci'

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def version
    def name
    def scmVars

    dockerNode(dockerImage: 'stakater/frontend-tools:latest') {
        container(name: 'docker') {
            stage("Checkout") {
                scmVars = checkout scm
                def js_package = readJSON file: 'package.json'
                def version_old = js_package.version.tokenize(".")
                version = "${version_old[0]}.${version_old[1]}.${env.BUILD_NUMBER}"
                name = js_package.name
                currentBuild.displayName = "${name}/${version}"
            }

            stage("Install") {
                withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                  variable: 'NEXUS_NPM_AUTH']]) {
                    sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn version --no-git-tag-version --new-version ${version}"
                    sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn install"
                }
            }

            stage("Lint") {
                withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                  variable: 'NEXUS_NPM_AUTH']]) {
                    try {
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn lint -f junit -o lint-report.xml"
                    } finally {
                        junit 'lint-report.xml'
                    }
                }
            }

            stage("Test") {
                withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                  variable: 'NEXUS_NPM_AUTH']]) {
                    try {
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn test-ci"
                    } finally {
                        junit 'junit.xml'
                    }
                }
            }

            stage("Build") {
                withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                  variable: 'NEXUS_NPM_AUTH']]) {
                    sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn build"
                }
            }

            stage("Tag") {
                withCredentials([usernamePassword(credentialsId: credentialId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                    sh """
                        git config user.name "${scmVars.GIT_AUTHOR_NAME}" # TODO move to git config
                        git config user.email "${scmVars.GIT_AUTHOR_EMAIL}"
                        git tag -am "By ${currentBuild.projectName}" v${version}
                        git push https://${GIT_USERNAME}:${GIT_PASSWORD}@${scmVars.GIT_URL.substring(8)} v${version}
                    """
                }
            }

            stage("Publish to nexus") {
                withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                  variable: 'NEXUS_NPM_AUTH']]) {
                    sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} npm publish"
                }
            }

            stage("Upload to S3") {
                s3Upload(file: 'lib/', bucket: "${params.BUCKET}", path: "${name}/${version}/")
            }

        }
    }
}
