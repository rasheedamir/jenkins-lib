import groovy.json.JsonSlurper

def call(String artifact, String version, String nexusHost) {

    withCredentials([[$class: 'StringBinding', credentialsId: 'NEXUS_NPM_AUTH',
                      variable: 'token']]) {
        echo "Looking for artifact with name=${artifact} and version=${version}"
        def nexusRestAPIVersion = "beta"
        def searchConnection = new URL("https://${nexusHost}/service/siesta/rest/${nexusRestAPIVersion}/search?q=${artifact}&version=${version}").openConnection()
        searchConnection.setRequestMethod("GET")
        searchConnection.setRequestProperty("Authorization", "Basic ${token}")
        echo "Response code for GET request  is ${searchConnection.getResponseCode().toString()}"
        def items = new JsonSlurper().parse(searchConnection.getInputStream()).get("items")
        items.each {
            echo "Deleting artifact ${it.name} ${it.version} in repository ${it.repository}"
            def deleteConnection = new URL("https://${nexusHost}/service/siesta/rest/${nexusRestAPIVersion}/components/${it.id}").openConnection()
            deleteConnection.setRequestMethod("DELETE")
            deleteConnection.setRequestProperty("Authorization", "Basic ${token}")
            echo "Response code for DELETE request is ${deleteConnection.getResponseCode().toString()}"
        }
        return null
    }
}
