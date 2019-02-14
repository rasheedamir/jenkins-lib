import kops.*

def call(Map config) {

    KopsBuildItem options = config.options
    if (options == null) {
        error("Error - options are null. Exiting...")
    }

    String scriptDir = "scripts" // Directory containing shell scripts for preparing AWS, kubecfg and for executing kops commands

    timestamps {
        clientsK8sNode(clientsImage: options.pipelineToolsImage) {

            container('clients') {

                stage('Checkout scm') {
                    checkout scm
                }

                stage('Prepare env') {
                    sh """
                        mkdir ${scriptDir}
                    """
                    checkoutRepo(options.kopsRepo, options.kopsRepoBranch, scriptDir, options.gitCredID)
                    prepareAWS(options.inputFileName, scriptDir)
                    
                }

                if (options.isDEPLOY) {

                    stage('Deploy') {

                        println "Deploy has been set to TRUE, Applying changes..."

                        Boolean isDRYRUN = false

                        if (options.doACTION == "UPDATE") {
                            //println "DEBUG: RUNNING UPDATE ACTION"
                            updateCluster(options.inputFileName, scriptDir, isDRYRUN)
                        } else if (options.doACTION == "CREATE") {
                            //println "DEBUG: RUNNING CREATE ACTION"
                            createCluster(options.inputFileName, scriptDir, isDRYRUN)
                        } else if (options.doACTION == "DELETE") {
                            //println "DEBUG: RUNNING DELETE ACTION"
                            deleteCluster(options.inputFileName, scriptDir, isDRYRUN)
                        } else if (options.doACTION == "VALIDATE") {
                            //println "DEBUG: RUNNING VALIDATE ACTION"
                            validateCluster(options.inputFileName, scriptDir)
                        } else {
                            println "No ACTION..."
                        }
                    
                    }

                } else {
                    
                    stage('Dry-run') {

                        println "Deploy has been set to FALSE, Doing dry-run..."

                        Boolean isDRYRUN = true

                        if (options.doACTION == "UPDATE") {
                            //println "DEBUG: RUNNING UPDATE ACTION"
                            updateCluster(options.inputFileName, scriptDir, isDRYRUN)
                        } else if (options.doACTION == "CREATE") {
                            //println "DEBUG: RUNNING CREATE ACTION"
                            createCluster(options.inputFileName, scriptDir, isDRYRUN)
                        } else if (options.doACTION == "DELETE") {
                            //println "DEBUG: RUNNING DELETE ACTION"
                            deleteCluster(options.inputFileName, scriptDir, isDRYRUN)
                        } else if (options.doACTION == "VALIDATE") {
                            //println "DEBUG: RUNNING VALIDATE ACTION"
                            validateCluster(options.inputFileName, scriptDir)
                        } else {
                            println "No ACTION..."
                        }

                    }

                }
                
            }

        }

    }

}

def checkoutRepo(String repoUrl, String branchName, String checkoutDir, String credID) {
    //println "DEBUG: CHECKOUT REPO"
    dir(checkoutDir){
        git (
            branch: branchName,
            url: repoUrl,
            credentialsId: credID
        )
    }
}

def prepareAWS(String inputFile, String scriptDir) {
    //println "DEBUG: PREPARE AWS"
    sh """
        mkdir ${HOME}.aws
        source ./vars.sh
        ${scriptDir}/prepareAWSConfig.sh
    """
}

def updateCluster(String inputFile, String scriptDir, Boolean isDRYRUN) {
    //println "DEBUG: UPDATE CLUSTER"
    if (isDRYRUN) {
        //println "DEBUG: DRYRUN"
        sh """
            source ./vars.sh
            ${scriptDir}/prepareTemplate.sh
            ${scriptDir}/exportKubecfg.sh
            ${scriptDir}/replaceCluster.sh
        """
    } else {
        //println "DEBUG: NO DRYRUN"
        sh """
            source ./vars.sh
            ${scriptDir}/prepareTemplate.sh
            ${scriptDir}/exportKubecfg.sh
            ${scriptDir}/replaceCluster.sh yes && ${scriptDir}/validateCluster.sh
        """
    }
}

def createCluster(String inputFile, String scriptDir, Boolean isDRYRUN) {
    //println "DEBUG: CREATE CLUSTER"
    if (isDRYRUN) {
        //println "DEBUG: DRYRUN"
        sh """
            source ./vars.sh
            ${scriptDir}/prepareTemplate.sh
            ${scriptDir}/createCluster.sh
        """
    } else {
        //println "DEBUG: NO DRYRUN"
        sh """
            source ./vars.sh
            ${scriptDir}/prepareTemplate.sh
            ${scriptDir}/createCluster.sh yes && ${scriptDir}/exportKubecfg.sh && ${scriptDir}/validateCluster.sh
        """
    }
}

def deleteCluster(String inputFile, String scriptDir, Boolean isDRYRUN) {
    //println "DEBUG: DELETE CLUSTER"
    if (isDRYRUN) {
        //println "DEBUG DRYRUN"
        sh """
            source ./vars.sh
            ${scriptDir}/prepareTemplate.sh
            ${scriptDir}/exportKubecfg.sh
            ${scriptDir}/deleteCluster.sh
        """
    } else {
        //println "DEBUG: NO DRYRUN"
        sh """
            source ./vars.sh
            ${scriptDir}/prepareTemplate.sh
            ${scriptDir}/exportKubecfg.sh
            ${scriptDir}/deleteCluster.sh
        """
        
        clusterName = sh (returnStdout: true, script: "grep 'CLUSTER_NAME=' ${inputFile} | cut -d'=' -f2")

        script {
            timeout(time: 10, unit: 'MINUTES') {
                input(id: "Delete Cluster", message: "Delete Cluster ${clusterName}?", ok: 'Delete')
            }
        }

        sh """
            source ./vars.sh
            ${scriptDir}/prepareTemplate.sh
            ${scriptDir}/exportKubecfg.sh
            ${scriptDir}/deleteCluster.sh yes
        """
    }
}

def validateCluster(String inputFile, String scriptDir) {
        sh """
            source ./vars.sh
            ${scriptDir}/exportKubecfg.sh
            ${scriptDir}/validateCluster.sh
        """
}
