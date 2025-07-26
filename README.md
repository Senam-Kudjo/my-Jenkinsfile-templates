# Jenkins-GitHub-container-image-fixer
# A pipeline script in Jenkins that sets the new container image into your GIToPS repo
# Do not forget to add the variables in the script into your Jenkins pipeline configuration


node {

    stage('Checkout Repository') {
        checkout scm: scmGit(branches: [[name: '*/master']], extensions: [], userRemoteConfigs: [[credentialsId: 'bf5de993-b44a-4c70-acf9-bc05ded62180', url: 'https://github.com/etranzact-gh/iac_main_kubernetes_argocd_project.git']])

    }

    stage('Update Configfile') { // 
            script {
                echo "BUILD_TRIGGERED_BY: ${BUILD_TRIGGERED_BY}"
                //def encodedPassword = URLEncoder.encode("$GIT_PASSWORD",'UTF-8')
                //sh "git switch master"

                sh "sed -i \"s/${REPOSITORY_NAME}:.*/${REPOSITORY_NAME}:${IMAGETAG}/g\" ${ENVIRONMENT}/manifests/${SERVICE_NAME}/${DEPLOYMENT_MANIFEST_NAME}"
                sh " cat ${ENVIRONMENT}/manifests/${SERVICE_NAME}/${DEPLOYMENT_MANIFEST_NAME}"
            }
    }

    stage('Commit Changes ') {
      script{
            withCredentials([usernamePassword(credentialsId: 'bf5de993-b44a-4c70-acf9-bc05ded62180', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
             sh """ 
                git config user.email devopsgh@etranzact.com.gh
                git config user.name etranzact-gh-cicd
                git add .
                git commit -m 'Updated Build ${REPOSITORY_NAME} With ${IMAGETAG}'
                git remote set-url origin https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/etranzact-gh/iac_main_kubernetes_argocd_project.git
                git push origin HEAD:master
            """
            }
      }

    }
    
    stage('Post-Stage Actions') {
        script {
            cleanWs()
        }
    }
}
