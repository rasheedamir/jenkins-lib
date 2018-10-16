def call(body) {
    def credentialId = 'dd_ci'

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def version
    def name
    def scmVars

    timestamps {
        withSlackNotificatons() {

            dockerNode(dockerImage: 'stakater/frontend-tools:0.1.0-8.12.0') {
                container(name: 'docker') {
                    stage("Checkout") {
                        scmVars = checkout scm
                        def js_package = readJSON file: 'package.json'
                        def version_base = js_package.version.tokenize(".")
                        int version_last = sh(
                                script: "git tag | awk -F. 'BEGIN {print \"-1\"} /v${version_base[0]}.${version_base[1]}/{print \$3}' | sort -g  | tail -1",
                                returnStdout: true
                        )

                        version = "${version_base[0]}.${version_base[1]}.${version_last + 1}"
                        name = js_package.name
                        currentBuild.displayName = "${name}/${version}"
                    }

                    stage("Install Dependencies") {
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
                                sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} JEST_JUNIT_OUTPUT=\"./unit-test-report.xml\" yarn test-ci"
                            } finally {
                                junit 'unit-test-report.xml'
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
                        withAWS(role: "${ROLE_NAME}", roleAccount: "${ROLE_ACCOUNT_ID}", roleSessionName: 'jenkins-upload-microfront') {
                            s3Upload(file: 'lib/', bucket: "${params.NEW_BUCKET}", path: "${name}/${version}/")
                        }
                    }

                    stage('Selenium') {
                        def regressionBuild
                        try {
                            regressionBuild = build job: "system-test", parameters: [[$class: 'StringParameterValue', name: 'APP_PARAMS', value: "${name}=${version}"]]
                        } finally {
                            String text = "<h2>Downstream jobs</h2><a href=\"${regressionBuild.getAbsoluteUrl()}\">${regressionBuild.getProjectName()} ${regressionBuild.getDisplayName()} - ${regressionBuild.getResult()}</a>"
                            rtp(nullAction: '1', parserName: 'HTML', stableText: text, abortedAsStable: true, failedAsStable: true, unstableAsStable: true)
                        }
                    }
                }
            }
        }
    }
}
