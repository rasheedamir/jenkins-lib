#!/usr/bin/groovy
import jenkins.model.Jenkins



def call(body) {
    def fixed_message = "color: 'good',  FIXED -  Job: ${env.JOB_NAME},  BuildNr: ${currentBuild.displayName} (<${env.BUILD_URL}|Go to build>)"
    def fail_message = "color: 'danger', Build FAILED -  Job: ${env.JOB_NAME},  BuildNr: ${currentBuild.displayName} (<${env.BUILD_URL}|Go to build>)"

    try {
        body()
        currentBuild.result = "SUCCESS"
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e

    } finally {
        if (currentBuild.currentResult == 'FAILURE') {
            sendSlackNotification($fail_message)

        }
        if (currentBuild.previousBuild?.result == "FAILURE" && currentBuild.currentResult == 'SUCCESS') {
            sendSlackNotification($fixed_message)
        }
    }
}

def sendSlackNotification(String message) {
    def credentialsId = 'slack_token'
    def channelName = '#jenkinstest'
    def token = getSlackToken(credentialsId)

    slackSend channel: "${channelName}",
            "${message}",
            teamDomain: 'digitialdealer',
            token: "${token}"

}

// See section "Technical Design": https://github.com/jenkinsci/workflow-cps-plugin/blob/master/README.md
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




