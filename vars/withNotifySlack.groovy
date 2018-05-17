#!/usr/bin/groovy

def call(body) {

    try {
        body()
        error("error")
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
        } else if (currentBuild.currentResult == 'SUCCESS') {
            echo "no slack msg sent"
        }
    }


}
