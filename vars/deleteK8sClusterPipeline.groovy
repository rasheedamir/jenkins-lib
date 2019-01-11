def call(body){

    clientsK8sNode(clientsImage: 'stakater/pipeline-tools:v1.16.3') {
        container('clients') {
                
            stage('Prepare') {
                sh """
                    mkdir cluster-tools
                    mkdir cluster-spec
                """
                dir('cluster-spec'){
                    checkout scm
                }
                dir('cluster-tools'){
                    git (
                        url: "https://gitlab.com/digitaldealer/infra/kops-cluster-scripts.git",
                        credentialsId: 'dd_ci'
                    )
                }
            }

            stage('Delete') {
                sh """
                    mkdir ${HOME}.aws
                    cd cluster-spec
                    source vars.sh
                    ../cluster-tools/prepareAWSConfig.sh
                    ../cluster-tools/prepareTemplate.sh
                    ../cluster-tools/exportKubecfg.sh
                    ../cluster-tools/deleteCluster.sh
                    ../cluster-tools/deleteCluster.sh yes
                """        
            }
        }
    }

}
