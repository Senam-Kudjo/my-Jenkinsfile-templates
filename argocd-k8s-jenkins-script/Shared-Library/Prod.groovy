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
        // stage('Quality Gates Inspection') {
        //     steps {
        //         script{
        //             waitForQualityGate abortPipeline: false
        //         }
        //     }

        // }


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
                    mvn clean package -DskipTests=true -Dquarkus.package.jar.type=uber-jar
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
        stage ('Build Docker Image & Push To ECR') {
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

    // --------------------------
    // Determine build type
    // --------------------------
    def isDevelop = (env.BRANCH_NAME == 'develop')
    def isProd = ['production'].contains(env.BRANCH_NAME)

    // --------------------------
    // Get project version (once)
    // --------------------------
    def version = "unknown"

    if (fileExists('pom.xml')) {
        version = readMavenPom(file: 'pom.xml').version
    }
    else if (fileExists('package.json')) {
        version = readJSON(file: 'package.json').version
    }

    // --------------------------
    // Build tag
    // --------------------------
    if (isDevelop) {
        TAG = "${DEV_BUILD_TAG}-${version}-${env.BUILD_NUMBER}"
        echo "Building Development Image: ${TAG}"
    }
    else if (isProd) {
        TAG = "${PROD_BUILD_TAG}-${version}-${env.BUILD_NUMBER}"
        echo "Building Production Image: ${TAG}"
    }

    // --------------------------
    // Build & Push
    // --------------------------
    def app = docker.build("${REPOSITORY_NAME}:${TAG}")

    docker.withRegistry("${ECR_Repository_URL}") {
        app.push(TAG)
    }

    env.TAG = TAG
    echo "Successfully built and pushed image with tag: ${TAG}"
}
}
        }


    // Deploy to Production with Approval
stage('Deploy To Production Environment') {
    when {
        anyOf {
            branch 'production'
            expression {
                return env.BRANCH_NAME.startsWith('release/')
            }
        }
    }
    steps {
        script {

            // def approval = input(
            //     id: 'ProductionApproval',
            //     message: 'Do you want to deploy to the Production Cluster?',
            //     ok: 'Submit',
            //     parameters: [
            //         choice(
            //             name: 'APPROVAL',
            //             choices: ['yes', 'no'],
            //             description: 'Select yes to proceed, no to abort'
            //         )
            //     ]
            // )

            // if (approval != 'yes') {
            //     error('Deployment to Production was rejected by user')
            // }

            echo "Approval granted — deploying to Production"

            def REPOSITORY_NAME = "${env.GIT_URL.split('/').last().replace('.git', '').toLowerCase()}"
            def DEPLOYMENT_MANIFEST_NAME = "${REPOSITORY_NAME}.yaml"
            def SERVICE_NAME = "${REPOSITORY_NAME}"

            echo "Image Tag: ${TAG}"
            echo "New Image Name: ${ECR_Repository_URL}/${REPOSITORY_NAME}:${TAG}"

            build job: 'gh-argocd-production-auto', parameters: [
                string(name: 'PORTINGO', value: "${PORTINGO}"),
                string(name: 'IMAGETAG', value: "${TAG}"),
                string(name: 'DEPLOYMENT_MANIFEST_NAME', value: "${DEPLOYMENT_MANIFEST_NAME}"),
                string(name: 'REPOSITORY_NAME', value: "${REPOSITORY_NAME}"),
                string(name: 'SERVICE_NAME', value: "${SERVICE_NAME}"),
                string(name: 'ENVIRONMENT', value: 'production')
            ]
        }
    }
}

    }
post {
    success {
        cleanWs()
    }
}
}
}
