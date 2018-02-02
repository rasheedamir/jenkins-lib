#!/usr/bin/groovy

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def expose = config.exposeApp ?: 'true'
    def requestCPU = config.resourceRequestCPU ?: '0'
    def requestMemory = config.resourceRequestMemory ?: '0'
    def limitCPU = config.resourceLimitMemory ?: '0'
    def limitMemory = config.resourceLimitMemory ?: '0'
    def ingressClass = config.ingressClass ?: 'unknown'
    def readinessProbePath = config.readinessProbePath ?: "/"
    def livenessProbePath = config.livenessProbePath ?: "/"

    def yaml

    def fabric8Registry = ''
    if (env.FABRIC8_DOCKER_REGISTRY_SERVICE_HOST) {
        fabric8Registry = env.FABRIC8_DOCKER_REGISTRY_SERVICE_HOST
    }

    def project = project
    
    
    def list = """
---
apiVersion: v1
kind: List
items:
"""

def service = """
- apiVersion: v1
  kind: Service
  metadata:
    annotations:
      fabric8.io/iconUrl: ${config.icon}
      fabric8.io/ingress.annotations: |-
        ingress.kubernetes.io/force-ssl-redirect: true
        kubernetes.io/ingress.class: ${ingressClass}
    labels:
      provider: fabric8
      project: ${project}
      expose: '${expose}'
      version: ${config.version}
    name: ${project}
  spec:
    ports:
    - port: 80
      protocol: TCP
      targetPort: ${config.port}
    selector:
      project: ${project}
      provider: fabric8
"""

def deployment = """
- apiVersion: extensions/v1beta1
  kind: Deployment
  metadata:
    annotations:
      fabric8.io/iconUrl: ${config.icon}
    labels:
      provider: fabric8
      project: ${project}
      version: ${config.version}
    name: ${project}
  spec:
    replicas: 1
    selector:
      matchLabels:
        provider: fabric8
        project: ${project}
    template:
      metadata:
        labels:
          provider: fabric8
          project: ${project}
          version: ${config.version}
      spec:
        imagePullSecrets:
        - name: ${config.dockerRegistrySecret}
        containers:
        - env:
          - name: KUBERNETES_NAMESPACE
            valueFrom:
              fieldRef:
                fieldPath: metadata.namespace
          image: ${fabric8Registry}/${project}:${config.version}
          imagePullPolicy: IfNotPresent
          name: ${project}
          ports:
          - containerPort: ${config.port}
            name: http
          resources:
            limits:
              cpu: ${requestCPU}
              memory: ${requestMemory}
            requests:
              cpu: ${limitCPU}
              memory: ${limitMemory}
          readinessProbe:
            httpGet:
              path: "${readinessProbePath}"
              port: ${config.port}
            initialDelaySeconds: 1
            timeoutSeconds: 5
            failureThreshold: 5
          livenessProbe:
            httpGet:
              path: "${livenessProbePath}"
              port: ${config.port}
            initialDelaySeconds: 180
            timeoutSeconds: 5
            failureThreshold: 5
        terminationGracePeriodSeconds: 2
"""

    yaml = list + service + deployment

    echo 'using resources:\n' + yaml
    return yaml
}
