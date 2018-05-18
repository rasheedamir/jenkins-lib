#!/usr/bin/groovy

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config

    try {
        body()
        error("Build failed")
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e

    } finally {
        if (currentBuild.currentResult == 'FAILURE') {
            withCredentials([string(credentialsId: 'slack_token', variable: 'credentialId')]) {
                slackSend channel: '#redlamp',
                        color: 'danger',
                        message: "Build FAILED -  Job: ${env.JOB_NAME},  BuildNr: ${currentBuild.displayName} (<${env.BUILD_URL}|Go to build>)",
                        teamDomain: 'digitialdealer',
                        token: $credentialId
            }
        }
    }


}
