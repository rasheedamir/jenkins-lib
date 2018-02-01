def call(body) {

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    dockerNode(dockerImage: 'stakater/node:6.9') {
        container(name: 'docker') {
            try {
                stage("Checkout") {
                    checkout scm
                }

                stage("Install") {
                    withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                      variable: 'NEXUS_NPM_AUTH']]) {
                        sh "npm install yarn -g"
//                      sh "yarn version --no-git-tag-version --new-version \$(cat package.json | awk -F\\\" '/\\\"version\\\"/{print \$4}')-\$(date -u +'%Y%m%dT%H%M%S')-\$(git rev-parse HEAD)"
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn version --no-git-tag-version --new-version \$(cat package.json | awk -F\\\" '/\\\"version\\\"/{print \$4}')-\$(date -u +'%Y%m%dT%H%M%S')-githash"
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn install"
                    }
                }

                stage("Publish to nexus") {
                    withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                      variable: 'NEXUS_NPM_AUTH']]) {
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} npm publish"
                    }
                }

            } finally {
//            zip zipFile: 'output.zip', dir: 'target', archive: true
//            archiveArtifacts artifacts: 'target/screenshots/*'
//            junit 'target/surefire-reports/*.xml'
            }
        }
    }
}
