#!/usr/bin/groovy
import jenkins.model.Jenkins

def call(body) {
    def credentialsId = 'slack_token'
    def jenkins_creds = Jenkins.instance.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0]
    try {
        body()
        error("Build failed")
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e

    } finally {


        String slacktoken = jenkins_creds.getStore().getDomains().findResult { domain ->
            jenkins_creds.getCredentials(domain).findResult { credential ->
                if (slackCredentialsId.equals(credentialsId)) {
                    credential.getSecret()
                }
            }
        }
        println "Slack token credentials: ${slacktoken}"

        if (currentBuild.currentResult == 'FAILURE') {
            slackSend channel: '#redlamp',
                    color: 'danger',
                    message: "Build FAILED -  Job: ${env.JOB_NAME},  BuildNr: ${currentBuild.displayName} (<${env.BUILD_URL}|Go to build>)",
                    teamDomain: 'digitialdealer',
                    token: $ { slacktoken }

        }
    }


}
