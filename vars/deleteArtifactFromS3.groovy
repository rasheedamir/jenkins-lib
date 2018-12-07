void call(String roleName, String roleAccountId, String bucket, String path) {

    withAWS(role: roleName, roleAccount: roleAccountId, roleSessionName: 'jenkins-upload-microfront') {
        try {
            echo "Looking for artifact in S3"
            s3FindFiles(bucket: "${bucket}")
            echo "Artifact found in S3"
            echo "Deleting artifact from S3"
            s3Delete(bucket: bucket, path: path)
        }
        catch (Exception exception) {
            echo "Artifact not found in S3"
        }
    }
}
