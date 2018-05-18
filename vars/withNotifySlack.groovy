#!/usr/bin/groovy
import jenkins.model.Jenkins
def config = [:]
body.resolveStrategy = Closure.DELEGATE_FIRST
body.delegate = config


def call(body) {
    def credentialsId = 'slack_token'
    def jenkins_creds = Jenkins.instance.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0]

    try {
        body()
       // error("Build failed")
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e

    } finally {
        String slackToken = jenkins_creds.getStore().getDomains().findResult { domain ->
            jenkins_creds.getCredentials(domain).findResult { credential ->
                if (credentialsId.equals(credential.id)) {
                    credential.getSecret()
                }
            }
        }

        if (currentBuild.currentResult == 'FAILURE') {
            slackSend channel: '#redlamp',
                    color: 'danger',
                    message: "Build FAILED -  Job: ${env.JOB_NAME},  BuildNr: ${currentBuild.displayName} (<${env.BUILD_URL}|Go to build>)",
                    teamDomain: 'digitialdealer',
                    token: "${slackToken}"

        }
    }


}
