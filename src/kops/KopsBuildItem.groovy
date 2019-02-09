package kops


public class KopsBuildItem implements Serializable{
    String inputFileName
    String gitCredID
    String kopsRepo
    String kopsRepoBranch
    String pipelineToolsImage
    String doACTION
    Boolean isDEPLOY
}