def call(body) {
    def credentialId = 'dd_ci'

    def config = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def mergeRequestBuild = params.MERGE_REQUEST_BUILD ?: false
    echo "mergeRequestBuild: ${mergeRequestBuild}"
    echo "checkoutBranch: ${env.gitlabBranch}"

    assert !(mergeRequestBuild && env.gitlabSourceBranch == null)
    def branchName = mergeRequestBuild ? env.gitlabSourceBranch : env.gitlabBranch ?: 'master'
    def secondaryNexusHost = params.SECONDARY_MAVEN_REPO
    def buildVersion
    def scmVars
    def name

    timestamps {
        withSlackNotificatons(isMergeRequestBuild: mergeRequestBuild) {
            dockerNode(dockerImage: 'stakater/builder-node-8:v0.0.2') {
                container(name: 'docker') {
                    try {
                        stage("Checkout") {
                            scmVars = checkout([$class: 'GitSCM', branches: [[name: branchName]], userRemoteConfigs: scm.getUserRemoteConfigs()])
                            def js_package = readJSON file: 'package.json'
                            name = js_package.name
                            def version_base = js_package.version.tokenize(".")
                            buildVersion = mergeRequestBuild ? getMRVersion(version_base, branchName, currentBuild) : getBJVersion(version_base)
                            currentBuild.displayName = "${buildVersion}"
                        }

                        stage("Install Dependencies") {
                            withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                              variable: 'NEXUS_NPM_AUTH']]) {
                                sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn version --no-git-tag-version --new-version ${buildVersion}"
                                sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn install"
                            }
                        }

                        stage("Lint") {
                            withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                              variable: 'NEXUS_NPM_AUTH']]) {
                                try {
                                    sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn lint -f junit -o lint-report.xml"
                                } finally {
                                    junit 'lint-report.xml'
                                }
                            }
                        }

                        stage("Test") {
                            withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                              variable: 'NEXUS_NPM_AUTH']]) {
                                try {
                                    sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} JEST_JUNIT_OUTPUT=\"./unit-test-report.xml\" yarn test-ci"
                                } finally {
                                    junit 'unit-test-report.xml'
                                }
                            }
                        }

                        stage("Build") {
                            withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                              variable: 'NEXUS_NPM_AUTH']]) {
                                sh "NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn build"
                            }
                        }

                        stage("Tag") {
                            if (!mergeRequestBuild) {
                                withCredentials([usernamePassword(credentialsId: credentialId, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                                    sh """
                                        git config user.name "${scmVars.GIT_AUTHOR_NAME}" # TODO move to git config
                                        git config user.email "${scmVars.GIT_AUTHOR_EMAIL}"
                                        git tag -am "By ${currentBuild.projectName}" v${buildVersion}
                                        git push https://${GIT_USERNAME}:${GIT_PASSWORD}@${scmVars.GIT_URL.substring(8)} v${
                                                    buildVersion
                                                }
                                    """
                                }
                            } else {
                                echo "Not tagging this build as it is a merge request build"
                            }
                        }

                        stage("Publish to nexus") {
                            if (!mergeRequestBuild) {
                                withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                                  variable: 'NEXUS_NPM_AUTH']]) {
                                    sh """
                                        export NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH}; 
                                        npm publish
                                    """
                                    try {
                                        sh """
                                            cat package.json | jq '.publishConfig.registry = "https://${secondaryNexusHost}/repository/npm-internal"' >> package.json
                                            npm publish
                                        """
                                    }
                                    catch (Exception ex) {
                                        println "WARNING: Deployment to alternate Nexus failed"
                                        println "Pipeline Will continue"
                                    }
                                }
                            } else {
                                echo "Not publishing this artifact to nexus as it is a merge request build"
                            }
                        }

                        stage("Upload to S3") {
                            s3Upload(file: 'lib/', bucket: "${params.BUCKET}", path: "${name}/${buildVersion}/")
                            withAWS(role: "${ROLE_NAME}", roleAccount: "${ROLE_ACCOUNT_ID}", roleSessionName: 'jenkins-upload-microfront') {
                                s3Upload(file: 'lib/', bucket: "${params.NEW_BUCKET}", path: "${name}/${buildVersion}/")
                            }
                        }

                        stage("Run cypress") {
                            git (
                                url: "https://gitlab.com/digitaldealer/frontend/apps/app.git",
                                credentialsId: 'dd_ci'
                            )
                            withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                                                  variable: 'NEXUS_NPM_AUTH']]) {
                                sh """
                                    NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} yarn --cwd app/ install
                                    NEXUS_NPM_AUTH=${NEXUS_NPM_AUTH} CYPRESS_queryString=${name}=${buildVersion} yarn --cwd app/ test:cypress-run-external
                                """
                            }
                        }
                    }
                    finally {
                        stage('Publish results') {
                            if (fileExists('cypress/screenshots')) {
                                zip zipFile: 'output_screenshots.zip', dir: 'cypress/screenshots/', archive: true
                                archiveArtifacts artifacts: 'cypress/screenshots/**/*', allowEmptyArchive: true
                            }
                            if (fileExists('cypress/videos')) {
                                zip zipFile: 'output_videos.zip', dir: 'cypress/videos/', archive: true
                                archiveArtifacts artifacts: 'cypress/videos/*', allowEmptyArchive: true
                            }
                        }

                        if (mergeRequestBuild) {
                            deleteArtifactFromS3("${ROLE_NAME}", "${ROLE_ACCOUNT_ID}", "${params.NEW_BUCKET}", "${name}/${buildVersion}/")
                        }
                    }
                }
            }
        }
    }
}

String getBJVersion(version_base) {
    int version_last = sh(
            script: "git tag | awk -F. 'BEGIN {print \"-1\"} /v${version_base[0]}.${version_base[1]}/{print \$3}' | sort -g  | tail -1",
            returnStdout: true
    )
    return "${version_base[0]}.${version_base[1]}.${version_last + 1}"
}

String getMRVersion(version_base, branchName, currentBuild) {
    def buildNumber = currentBuild.number
    def bjVersion = getBJVersion(version_base)
    return "${bjVersion}-beta-${branchName}-${buildNumber}"
}
