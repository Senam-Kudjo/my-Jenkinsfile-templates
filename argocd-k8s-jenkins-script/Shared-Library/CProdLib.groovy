def call(Map config = [:]) {

def PORTINGO = config.get('PORTINGO', '')
    
pipeline{
    
    agent { label 'amd-node' }
    
    tools {nodejs "NodeJS_22.16.0"}

    options {
        timestamps()
        timeout(time:20, unit:'MINUTES')
        disableResume()
        disableConcurrentBuilds abortPrevious: true

        office365ConnectorWebhooks([[
            name: 'Jenkins_MS_Teams_Notifier',
            startNotification: true,
            notifySuccess: true,
            notifyAborted: true,
            notifyFailure: true,
            url: "${Build_Alerts_WebHook_URL}"
        ]])
    }

    environment {
        RELEASE_TAG = "release"
        DEV_BUILD_TAG = "dev"
        PROD_BUILD_TAG = "prod"
        ECR_Repository_URL = "842092678711.dkr.ecr.eu-west-1.amazonaws.com"
    }


    stages{

        // Checkout Code From GitHub
        // stage("SourceCode CheckOut"){
        //     steps{
        //         script{
        //             checkout scm
        //         }
        //     }
        // }


        // Compile Source Code
stage('Compile Source Code') {
    when {
        expression {
            // Detect if Java or Node project
            fileExists('pom.xml') || fileExists('package.json')
        }
    }
    steps {
        script {
            if (fileExists('pom.xml')) {
                echo "Java project detected — running Maven compile..."
                sh 'mvn compile'
            } else if (fileExists('package.json')) {
                echo "Node.js project detected — running npm install..."
                sh '''
                     # Retry npm install to handle transient network errors
                  npm install 
                  # --no-fund --no-audit --legacy-peer-deps
                '''
            } else {
                echo "No recognized project type found — skipping compilation."
            }
        }
    }
}


        // Test Source Code
        // stage('Run Unit Test Cases') {
        //     steps {
        //             sh 'mvn test'
        //         }
        // }


   //     // Run Static Code Analysis
        stage('Code Quality Checks') {
            steps {
                script {
                    def scannerHome = tool 'sonarscaner';
                    def REPOSITORY_NAME = "${env.GIT_URL.split('/').last().replace('.git', '').toLowerCase()}"
                    withSonarQubeEnv('eTranzact GH Sonarqube Server') {
                        sh "${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=${REPOSITORY_NAME} -Dsonar.projectName=${REPOSITORY_NAME} -Dsonar.java.binaries=."
                    }
                }
        }

    }

   //     // Quality Gates Inspection
        stage('Quality Gates Inspection') {
            steps {
                script{
                    waitForQualityGate abortPipeline: false
                }
            }

        }


        // Build Code Into Deployable Artifact
stage('Build Artifact') {
    when {
        expression {
            fileExists('pom.xml') || fileExists('package.json')
        }
    }
    steps {
        script {
            if (fileExists('pom.xml')) {
                echo "Java project detected — building with Maven..."
                sh '''
                    mvn clean package -DskipTests=true -Dquarkus.package.type=uber-jar
                '''
            } else if (fileExists('package.json')) {
                echo "Node.js project detected — building production bundle..."
                sh '''
                    npm run build
                '''
            } else {
                echo "No recognized project type found — skipping artifact build."
            }
        }
    }
}



        // Package Deployable Artifact & Publish To ECR Storage Repository
        stage ('ECR Repo Checker and Creator') {
            when{
                anyOf {
                    branch 'production'
                    
                    expression {
                        return env.BRANCH_NAME.startsWith('release/')
                    }
                }
            }
steps {
    script {

    def REPOSITORY_NAME = "${env.GIT_URL.split('/').last().replace('.git', '').toLowerCase()}"
    echo "Using repository name: ${REPOSITORY_NAME}"

    // Login to ECR
    sh """
        aws ecr get-login-password --region eu-west-1 | \
        docker login --username AWS --password-stdin ${AWS_Account_URL}
    """

    // Ensure repository exists
    def repoStatus = sh(
        script: "aws ecr describe-repositories --repository-names ${REPOSITORY_NAME} --region eu-west-1",
        returnStatus: true
    )

    if (repoStatus != 0) {
        echo "ECR repository not found — creating it"
        sh "aws ecr create-repository --repository-name ${REPOSITORY_NAME} --region eu-west-1"
    }

    TAG = "${PROD_BUILD_TAG}-${env.BUILD_NUMBER}"
    env.TAG = TAG
    echo "Successfully built and pushed image with tag: ${TAG}"
}
}
        }


stage("Send JAR + Rebuild + Restart Container") {
    steps {
        script {
            def REPOSITORY_NAME = "${env.GIT_URL.split('/').last().replace('.git', '').toLowerCase()}"
            sshPublisher(
                publishers: [
                    sshPublisherDesc(
                        configName: 'server_cprod_aws',
                        transfers: [
                            sshTransfer(
                                sourceFiles: 'target/*.jar, lib/, Dockerfile',
                                // sourceFiles: 'lib/, Dockerfile',
                                remoteDirectory: "${REPOSITORY_NAME}",
                                flatten: false,
                                cleanRemote: false,
                                execCommand: """
                                    cd /opt/${REPOSITORY_NAME} || exit 1
                                    cp -r target/*.jar lib
                                    aws ecr get-login-password --region eu-west-1 | docker login --username AWS --password-stdin ${AWS_Account_URL}
                                    docker build -t ${REPOSITORY_NAME}:${TAG} .
                                    docker tag ${REPOSITORY_NAME}:${TAG} ${AWS_Account_URL}/${REPOSITORY_NAME}:${TAG}
                                    docker push ${AWS_Account_URL}/${REPOSITORY_NAME}:${TAG}

                                    echo "Stopping old containers..."
                                    docker ps -aq --filter "name=${REPOSITORY_NAME}" | xargs -r docker rm -f

                                    echo "Starting new container..."
                                    docker run -d --restart unless-stopped -p ${PORTINGO}:${PORTINGO} -v /opt/${REPOSITORY_NAME}:/app --name ${REPOSITORY_NAME} ${AWS_Account_URL}/${REPOSITORY_NAME}:${TAG}
                                """,
                                execTimeout: 300000,
                                makeEmptyDirs: false,
                                noDefaultExcludes: false,
                                patternSeparator: '[, ]+',
                                remoteDirectorySDF: false,
                                removePrefix: ''
                            )
                        ],
                        usePromotionTimestamp: false,
                        useWorkspaceInPromotion: false,
                        verbose: true
                    )
                ]
            )
        }
    }
}

// End of Upload Jar Stage


    }
//     // cleanup the workspace when build is successful
post {
    success {
        cleanWs()
    }
}

}
}
