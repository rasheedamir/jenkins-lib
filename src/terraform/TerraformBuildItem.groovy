package terraform


public class TerraformBuildItem implements Serializable{
    String[] inputDirs
    String inputFilesName
    String outputRepo
    String outputRepoBranch
    String outputDir
    String outputGitUser
    String outputGitEmail
    String gitCredID
    String terraformRepo
    String terraformRepoBranch
    Boolean isCD
}
