import terraform.*

def call(Map config) {

    TerraformBuildItem options = config.options
    if (options == null) {
        error("Error - options are null. Exiting...")
    }

    String tfScriptDir = "build"    // Directory that will contain neccessary scripts for preparing the AWS, Kubernetes and invoking Terraform
    String tfSourceDir = ""         // Directory that will contain the variables or configuration terraform will use
    String tfModuleDir = ""         // Directory that will contain terraform modules and/or configurations 

    timestamps {
        clientsK8sNode(clientsImage: 'stakater/pipeline-tools:v1.16.2') {

            container('clients') {

                stage('Checkout') {
                    checkout scm
                }

                stage('Prepare') {
                    setGitUserInfo(options.outputGitUser, options.outputGitEmail)
                    sh """
                        mkdir ${tfScriptDir}
                        mkdir ${options.outputDir}
                    """
                    checkoutRepo('https://' + options.terraformRepo, options.terraformRepoBranch, tfScriptDir, options.gitCredID)
                    prepareEnv(options.inputFilesName, tfScriptDir)
                    checkoutRepo('https://' + options.outputRepo, options.outputRepoBranch, options.outputDir, options.gitCredID)
                }

                stage('CI: Validate') {

                    for (int i = 0; i < options.inputDirs.length; i++) {
                        tfSourceDir = options.inputDirs[i]
                        if (tfSourceDir != "input-terraform") {
                            tfModuleDir = tfScriptDir + "/modules/terraform-postgres-module"
                            terraformPlan(tfScriptDir, tfModuleDir, tfSourceDir, options)
                        } else {
                            tfModuleDir = tfScriptDir + "/modules/terraform-main"
                            terraformPlan(tfScriptDir, tfModuleDir, tfSourceDir, options)    
                        }
                    }
                    
                }

                if (options.isCD) {

                    stage('CD: Deploy') {

                        println "Deploy has been set to true. Applying CD"

                        for (int i = 0; i < options.inputDirs.length; i++) {
                            tfSourceDir = options.inputDirs[i]
                            if (tfSourceDir != "input-terraform") {
                                tfModuleDir = tfScriptDir + "/modules/terraform-postgres-module"
                                terraformApply(tfScriptDir, tfModuleDir, tfSourceDir, options)
                                copyDir(tfModuleDir + '/*', options.outputDir + '/' + tfSourceDir)
                                copyDir(tfSourceDir + '/*', options.outputDir + '/' + tfSourceDir)
                                copyFile(options.inputFilesName, options.outputDir + '/' + tfSourceDir + '/common_' + options.inputFilesName)
                            } else {
                                tfModuleDir = tfScriptDir + "/modules/terraform-main"
                                terraformApply(tfScriptDir, tfModuleDir, tfSourceDir, options)
                                copyDir(tfModuleDir + '/*', options.outputDir + '/' + tfSourceDir)
                                copyDir(tfSourceDir + '/*', options.outputDir + '/' + tfSourceDir)
                                copyFile(options.inputFilesName, options.outputDir + '/' + tfSourceDir + '/common_' + options.inputFilesName)
                            }
                        }

                    }

                    stage('Commit') {

                        setGitUserInfo(options.outputGitUser, options.outputGitEmail)
                        commitGit(options.gitCredID, options.outputRepo, options.outputDir, "update infra state")

                    }
                } else {
                    println("Deploy has been set to false. Skipping CD")
                }
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

def checkoutRepo(String repoUrl, String branchName, String checkoutDir, String credID) {
    dir(checkoutDir){
        git (
            branch: branchName,
            url: repoUrl,
            credentialsId: credID
        )
    }
}

def prepareEnv(String inputFile, String tfScriptDir) {
    sh """
        mkdir ${HOME}.aws
        mkdir ${HOME}.kube
        ${tfScriptDir}/prepareAWSConfig.sh ${inputFile}
        ${tfScriptDir}/prepareKubeConfig.sh ${inputFile}
    """
}

def terraformPlan(String tfScriptDir, String tfModuleDir, String tfSourceDir, TerraformBuildItem options) {
    sh """
        set -o pipefail
        export workingDir=\$(pwd)
        cd ${tfModuleDir}
        \${workingDir}/${tfScriptDir}/init.sh \${workingDir}/${options.inputFilesName} \${workingDir}/${tfSourceDir}/${options.inputFilesName} | tee plan.txt
        rm --recursive --force .terraform
    """
}

def terraformApply(String tfScriptDir, String tfModuleDir, String tfSourceDir, TerraformBuildItem options) {
    sh """
        set -o pipefail
        export workingDir=\$(pwd) 
        cd ${tfModuleDir}
        \${workingDir}/${tfScriptDir}/apply.sh \${workingDir}/${options.inputFilesName} \${workingDir}/${tfSourceDir}/${options.inputFilesName} | tee apply.txt
        rm --recursive --force .terraform
    """
}

def copyDir(String sourceDir, String destDir) {
    sh """
        test -d ${destDir} || mkdir --parents --verbose ${destDir}
        cp --verbose ${sourceDir} ${destDir}
    """
}

def copyFile(String sourceFile, String destFile) {
    sh """
        test -f ${sourceFile} && cp --verbose ${sourceFile} ${destFile}
    """
}

def commitGit(String gitCredID, String repoUrl, String repoDir, String commitMessage) {
    withCredentials([usernamePassword(credentialsId: gitCredID, passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
        String messageToCheck = "nothing to commit, working tree clean"
        sh """
            cd ${repoDir}
            git add .
            if ! git status | grep '${messageToCheck}' ; then
                git commit -m "${commitMessage}"
                git push https://${GIT_USERNAME}:${GIT_PASSWORD}@${repoUrl}
            else
                echo \"nothing to do\"
            fi
        """
    }
}
