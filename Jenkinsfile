pipeline {
    agent any

    tools {
        gradle 'gradle'
        jdk '25'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                sh 'rm -f gradle.properties'
            }
        }

        stage('Build') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'nexus',
                    usernameVariable: 'ORG_GRADLE_PROJECT_astralrealmsUsername',
                    passwordVariable: 'ORG_GRADLE_PROJECT_astralrealmsPassword'
                )]) {
                    sh 'gradle build'
                }
            }
        }

        stage('Archive') {
            steps {
                archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
            }
        }

        stage('Deploy to Private Repo') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'nexus',
                    usernameVariable: 'ORG_GRADLE_PROJECT_astralrealmsUsername',
                    passwordVariable: 'ORG_GRADLE_PROJECT_astralrealmsPassword'
                )]) {
                    sh 'gradle publish'
                }
            }
        }
    }

    post {
        success { echo 'Build successful!' }
        failure { echo 'Build failed!' }
    }
}