#!/usr/bin/groovy
import jenkins.model.Jenkins


def call(body) {
    def credentialsId = 'slack_token'
    def channelName = '#jenkinstest'
    def success_message = "color: 'good', Build SUCCESS -  Job: ${env.JOB_NAME},  BuildNr: ${currentBuild.displayName} (<${env.BUILD_URL}|Go to build>)"
    def fail_message = "color: 'danger', Build FAILED -  Job: ${env.JOB_NAME},  BuildNr: ${currentBuild.displayName} (<${env.BUILD_URL}|Go to build>)"

    try {
        body()
        error("Build failed")
        //currentBuild.result = "SUCCESS"
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e

    } finally {
        if (currentBuild.currentResult == 'FAILURE') {
            sendSlackNotification(success_message)

        }
        if (currentBuild.previousBuild.result == "FAILURE" && currentBuild.currentResult == 'SUCCESS'){
            sendSlackNotification(fail_message)
        }
    }
}

def sendSlackNotification(String message){
    def token = getSlackToken(credentialsId)
    slackSend channel: "${channelName}" ,
            ${message},
            teamDomain: 'digitialdealer',
            token: "${token}"

}

    @NonCPS
    def getSlackToken(String creds) {
        def jenkins_creds = Jenkins.instance.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0]
        String slackToken = jenkins_creds.getStore().getDomains().findResult { domain ->
            jenkins_creds.getCredentials(domain).findResult { credential ->
                if (creds.equals(credential.id)) {
                    credential.getSecret()

                }
            }
        }
        return slackToken
}




