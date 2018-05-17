#!/usr/bin/groovy
def call() {
            slackSend channel: '#jenkinstest',
                    color: 'danger',
                    message: "Build FAILED -  Job: ${env.JOB_NAME} BuildNr: #${currentBuild.displayName} (<${env.BUILD_URL}|Go to build>)",
                    teamDomain: 'digitialdealer',
                    token: '8vmDPD2QkYIX0pu3Rcf3dA4i'


}
