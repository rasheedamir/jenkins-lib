// Requiers the CDN_ID and ASSUMING_ACCOUNT variables to be set
def call(body){

    clientsK8sNode(clientsImage: 'stakater/pipeline-tools:v1.16.2') {
        container('clients') {

            stage('Checkout i18n-locales repo') {

                sh """
                    mkdir workdir
                """
    
                dir('workdir') {
                    git (
                        url: "https://gitlab.com/digitaldealer/i18n-locales.git",
                        credentialsId: 'dd_ci'
                    )
                }

            }

            stage('Creating CDN cache invalidation') {

                sh """#!/bin/bash
                    cd workdir
                    BATCH=`git diff | git --no-pager show HEAD^2  --name-only | awk -v ORS=' ' -F'/' '/.json\$/ {print "/i18n-locales/"\$(NF-1)"/"\$NF}'`
                    echo "Paths: \${BATCH}"
                    echo "Distribustion ID: ${CDN_ID}"
                    mkdir ${HOME}.aws
                    cat <<- EOF > ${HOME}.aws/config
[profile cdn-profile]
role_arn = arn:aws:iam::${ASSUMING_ACCOUNT}:role/CDNRole
credential_source = Ec2InstanceMetadata
EOF
                    cat ${HOME}.aws/config
                    aws --profile cdn-profile cloudfront create-invalidation --distribution-id ${CDN_ID} --paths \${BATCH}
                """
            }

        }

    }

}
