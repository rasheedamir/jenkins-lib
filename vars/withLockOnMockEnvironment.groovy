#!/usr/bin/groovy

import groovy.json.JsonOutput

def call(Map parameters = [:], body) {

    int defaultLifetime = (20 + 5) * 60
    int defaultWait = 4;
    URL url = new URL("http://restful-distributed-lock-manager.tools:8080/locks/mock")

    String lockName = parameters.get('lockName')
    String lockJson = JsonOutput.toJson(
            [
                title: lockName,
                lifetime: defaultLifetime,
                wait: defaultWait
            ])


    while (!hasLock(url, lockJson)) {
        echo "Waiting for lock"
        sleep 4
    }

    body()

    releaseLock(url)
}

private boolean hasLock(URL url, String lockBody) {

    def connection = url.openConnection()
    connection.setDoOutput(true)
    def writer = new OutputStreamWriter(connection.getOutputStream())
    writer.write(lockBody)
    writer.flush()
    writer.close()

    def lockAcquired
    def responseCode = connection.getResponseCode()
    if (responseCode == 201) {
        def location = connection.getHeaderField("Location")
        echo "Acquired ${location}"
        lockAcquired = true;
    } else {
        echo "Did not get a lock"
        lockAcquired = false;
        if (responseCode != 408) {
            echo "Something went wrong when locking: ${responseCode}"
        }
    }

    return lockAcquired;
}

private void releaseLock(URL lockUrl) {
    echo "Releasing ${lockUrl}"
    def conn = lockUrl.openConnection()
    conn.setRequestMethod("DELETE")

    def responseCode = conn.getResponseCode()
    if (responseCode != 204) {
        echo "Something went wrong when releaseing the lock: ${responseCode}"
    }
}