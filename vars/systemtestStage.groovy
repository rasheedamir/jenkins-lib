import groovy.json.JsonOutput

def call(config, forceRollbackMicroService = false) {

    stage("System test") {
        def parameters = [
                [$class: 'StringParameterValue', name: 'config', value: JsonOutput.toJson(config)],
                [$class: 'BooleanParameterValue', name: 'forceRollbackMicroService', value: forceRollbackMicroService]
        ]
        def testJob = build job: "system-test", parameters: parameters, propagate: false

        node {
            String text = "<h2>Regression test</h2><a href=\"${testJob.getAbsoluteUrl()}\">${testJob.getProjectName()} ${testJob.getDisplayName()} - ${testJob.getResult()}</a>"
            rtp(nullAction: '1', parserName: 'HTML', stableText: text, abortedAsStable: true, failedAsStable: true, unstableAsStable: true)
        }

        if (testJob.getResult() != "SUCCESS") {
            error "System test failed"
        }
    }
}
