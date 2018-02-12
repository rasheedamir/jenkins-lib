def call(body) {

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    dockerNode(dockerImage: 'stakater/frontend-tools:latest') {
        container(name: 'docker') {
            try {
                stage("Checkout") {
                    checkout scm
                }

                stage("Install") {
                    withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                      variable: 'NEXUS_NPM_AUTH']]) {
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn version --no-git-tag-version --new-version \$(cat package.json | awk -F\\\" '/\\\"version\\\"/{print \$4}')-\$(date -u +'%Y%m%dT%H%M%S')-\$(git rev-parse HEAD)"
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn install"
                    }
                }

                stage("Lint") {
                    withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                      variable: 'NEXUS_NPM_AUTH']]) {
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn lint -f junit -o lint-report.xml"
                    }
                }

                stage("Test") {
                    withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                      variable: 'NEXUS_NPM_AUTH']]) {
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn add jest-junit"
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn test --testResultsProcessor=\"jest-junit\""
                    }
                }

                stage("Rollup") {
                    sh "npm install -g rollup"
                    sh "rollup -c"
                }

                stage("Publish to nexus") {
                    withCredentials([[$class  : 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                      variable: 'NEXUS_NPM_AUTH']]) {
                        sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} npm publish"
                    }
                }
            } finally {
                junit 'lint-report.xml'
                junit 'junit.xml'
            }
        }
    }
}
