package terraform


public class BuildOptions implements Serializable{
    String aws_access_key_id
    String aws_secret_access_key
    String aws_session_token
    Boolean isCD
    String database_password
    String outputRepo
    String outputDir
    String outputRepoBranch
    String outputGitUser
    String outputGitEmail
    String inputFilesName
    String backendBucket
    String awsRegion

}
