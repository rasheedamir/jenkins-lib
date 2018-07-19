import terraform.*

def call(Map config) {
// This would be a temporary file
    String secretsFile = "secrets.sh"

// will be retrieved from input file
    String region = ""
    AnsibleBuildItem[] ansibleBuildItems = config.ansibleBuildItems
    TerraformBuildItem[] terraformBuildItems = config.terraformBuildItems
    BuildOptions options = config.options
    if (ansibleBuildItems == null){
        ansibleBuildItems = []
    }
    if(terraformBuildItems == null ){
        terraformBuildItems = []
    }

    if(options == null){
        error("options are null exiting")
    }

    clientsK8sNode(clientsImage: 'stakater/pipeline-tools:dev') {

        container('clients') {

            stage('Checkout Code') {
                checkout scm
            }

            stage('Prepare') {

                // write secrets to file
                sh """
                touch ${secretsFile} 
                echo "\nexport TF_VAR_database_password=\"${options.database_password}\"" >> ${secretsFile}
                """

                setGitUserInfo(options.outputGitUser, options.outputGitEmail)

                // retrieve region from input file
                region = sh(returnStdout: true, script: """
                source ${options.inputFilesName}
                echo TF_VAR_region
                """).trim()

                persistAwsKeys(options.aws_access_key_id, options.aws_secret_access_key, options.aws_session_token, region)

                checkoutRepo(options.outputRepo, options.outputRepoBranch, options.outputDir)
            }

            stage('CI: Validate') {


                for (int i = 0; i < ansibleBuildItems.length; i++) {
                    AnsibleBuildItem item = ansibleBuildItems[i]
                    setGitUserInfo(item.gitUser, item.gitEmail)
                    checkoutRepo(item.repo, item.branch, item.localDir)
                    String buildDir = item.localDir + "/build/"
                    String actionDir = item.localDir + "/ansible/"
                    String playbookAction = "ansible-playbook configure.yaml"
                    build(pwd(), buildDir, item.localDir, options.inputFilesName, actionDir, playbookAction, secretsFile, options.outputDir)
                }
                 for (int i = 0; i < ansibleBuildItems.length; i++) {
                    printAnsibleItemPlan(ansibleBuildItems[i])
                 }
                for (int i = 0; i < terraformBuildItems.length; i++){
                    TerraformBuildItem item = terraformBuildItems[i]
                    terraformPlan(item, options)
                }
            }

            if (options.isCD) {

                stage('CD: Deploy') {

                    println("Deploy has been set to true. Applying CD")
                    // Delete modules dirs
                    sh "rm -rf terraform-module*/"
                    for (int i = 0; i < ansibleBuildItems.length; i++) {
                        AnsibleBuildItem item = ansibleBuildItems[i]

                        checkoutRepo(item.repo, item.branch, item.localDir)

                        String buildDir = item.localDir + "/build/"
                        String actionDir = item.localDir + "/ansible/"

                        // retrieve action from module input file
                        String action = sh(returnStdout: true, script: """
                        source input-${item.localDir}/${options.inputFilesName}
                        echo \$action
                        """).trim()

                        String playbookAction = "ansible-playbook " + action
                        build(pwd(), buildDir, item.localDir, options.inputFilesName, actionDir, playbookAction, secretsFile, options.outputDir)
                    }

                    for (int i = 0; i < terraformBuildItems.length; i++){
                        TerraformBuildItem item = terraformBuildItems[i]
                        terraformExecutePlan(item)
                        cleanTerraformFolder(item)
                        copyDir(item.localDir, options.outputDir)
                    }

                }

                stage('Clean') {
                    sh "rm ${secretsFile}"
                }

                stage('Commit') {

                    setGitUserInfo(options.outputGitUser, options.outputGitEmail)
                    commitChanges(options.outputDir, "update infra state")
                }
            } else {

                println("Deploy has been set to false. Skipping CD")

            }
        }
    }
}

def setGitUserInfo(String gitUserName, String gitUserEmail) {
    sh """
        git config --global user.name "${gitUserName}"
        git config --global user.email "${gitUserEmail}"
    """
}


def build(String workspace, String buildDir, String moduleDir, String inputFile, String actionDir, String action, String secretsFile, String outputDir) {


    sh """
        cd ${workspace}
        mkdir -p ${buildDir}
        # copy additional input file to build dir
        cp input-${moduleDir}/${inputFile} ${buildDir}
        # append an extra line into the additional input file in build dir
        echo "\n" >> ${buildDir}/${inputFile}
        # now append input file into the additional input file in build dir
        cat ${inputFile} >> ${buildDir}/${inputFile}
        # copy secrets file into the build dir
        cp ${secretsFile} ${buildDir}/
        # perform the action
        cd ${actionDir}
        ${action}
        cd ${workspace}
        # delete secrets file from the build dir
        rm -f ${buildDir}/${secretsFile}
        # now copy build dir contents into output repo to be commited
        mkdir -p ${outputDir}/${moduleDir}
        cp -r ${buildDir}/* ${outputDir}/${moduleDir}
    """
}

def printAnsibleItemPlan(AnsibleBuildItem item){
    sh """
        cat ${item.localDir}/build/plan.txt 
    """
}

def terraformPlan(TerraformBuildItem item, BuildOptions options){
    sh """
        #git ssh keys 
        chmod 600 /root/.ssh-git/ssh-key
        eval `ssh-agent -s`
        ssh-add /root/.ssh-git/ssh-key
        
        cd ${item.localDir}
        terraform init -reconfigure -backend=true -get=true -input=false \
            -backend-config="bucket=${options.backendBucket}" \
        -backend-config="key=${item.backendKey}" \
        -backend-config="region=${options.awsRegion}" \
        -no-color
        
        terraform plan -out=plan.txt -input=false
    """

}

def terraformExecutePlan(TerraformBuildItem item){
   sh """
        cd ${item.localDir}
        terraform apply -input=false -auto-approve plan.txt > apply.txt
   """
}
def cleanTerraformFolder(TerraformBuildItem item){
    sh """
        rm -r ${item.localDir}/.terraform
    
    """
}
def copyDir(String sourceDir, String destDir){
    sh """
        cp -r ${sourceDir} ${destDir} 
    """
}

def commitChanges(String repoDir, String commitMessage) {
    // TODO: Find a solution for eval `ssh-agent -s` runned everytime
    // https://aurorasolutions.atlassian.net/browse/STK-11
    String messageToCheck = "nothing to commit, working tree clean"
    sh """
        chmod 600 /root/.ssh-git/ssh-key
        eval `ssh-agent -s`
        ssh-add /root/.ssh-git/ssh-key
        cd ${repoDir}
        git add .
        if ! git status | grep '${messageToCheck}' ; then
            git commit -m "${commitMessage}"
            git push
        else
            echo \"nothing to do\"
        fi
    """
}


def persistAwsKeys(String aws_access_key_id, String aws_secret_access_key, String aws_session_token, String aws_region) {
    sh """
        cd \$HOME
        mkdir -p .aws/
        echo "[default]\naws_access_key_id = ${aws_access_key_id}\naws_secret_access_key = ${
        aws_secret_access_key
    }\naws_session_token = ${aws_session_token}" > .aws/credentials
        echo "[default]\nregion = ${aws_region}" > .aws/config
    """
}

def checkoutRepo(String repo, String branch, String dir) {
    // TODO: Find a solution for eval `ssh-agent -s` runned everytime
    // https://aurorasolutions.atlassian.net/browse/STK-11
    sh """
        chmod 600 /root/.ssh-git/ssh-key
        eval `ssh-agent -s`
        ssh-add /root/.ssh-git/ssh-key

        rm -rf ${dir}
        git clone -b ${branch} ${repo} ${dir}
    """
}

