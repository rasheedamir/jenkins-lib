#!/usr/bin/groovy
import jenkins.model.Jenkins


def call(body) {
    def credentialsId = 'slack_token'

    try {
        //body()
        error("Build failure")
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e

    } finally {
        if (currentBuild.currentResult == 'FAILURE') {
            def token = getSlackToken(credentialsId)
            slackSend channel: '#jenkinstest',
                    color: 'danger',
                    message: "Build FAILED -  Job: ${env.JOB_NAME},  BuildNr: ${currentBuild.displayName} (<${env.BUILD_URL}|Go to build>)",
                    teamDomain: 'digitialdealer',
                    token: "${token}"

        }
    }
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




