#!/usr/bin/groovy

def call(body) {

    try {
        body()
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e

    } finally {
        if (currentBuild.currentResult == 'FAILURE') {
            slackSend channel: '#redlamp',
                    color: 'danger',
                    message: "Build FAILED -  Job: ${env.JOB_NAME},  BuildNr: ${currentBuild.displayName} (<${env.BUILD_URL}|Go to build>)",
                    teamDomain: 'digitialdealer',
                    token: '8vmDPD2QkYIX0pu3Rcf3dA4i'
        }
    }


}
