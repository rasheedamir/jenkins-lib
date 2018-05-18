#!/usr/bin/groovy

def call(body) {
    def credentialsId = 'slack_token'
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config

    try {
      // body()
        error("Build failed")
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e

    } finally {
        if (currentBuild.currentResult == 'FAILURE') {
            withCredentials([string(credentialsId: credentialsId, variable: 'token')])
                slackSend channel: '#redlamp',
                        color: 'danger',
                        message: "Build FAILED -  Job: ${env.JOB_NAME},  BuildNr: ${currentBuild.displayName} (<${env.BUILD_URL}|Go to build>)",
                        teamDomain: 'digitialdealer',
                        token: $token
        }
    }


}
