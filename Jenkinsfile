pipeline {
    agent any

    tools {
        jdk '25'
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
        disableConcurrentBuilds()
    }

    environment {
        GRADLE_OPTS = '-Dorg.gradle.daemon=false'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                sh '''
                    rm -f gradle.properties
                    chmod +x gradlew
                '''
            }
        }

        stage('Build') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'nexus',
                    usernameVariable: 'ORG_GRADLE_PROJECT_astralrealmsUsername',
                    passwordVariable: 'ORG_GRADLE_PROJECT_astralrealmsPassword'
                )]) {
                    sh './gradlew --no-daemon --stacktrace build'
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
                    sh './gradlew --no-daemon --stacktrace publish'
                }
            }
        }
    }

    post {
        success { echo 'Build successful!' }
        failure { echo 'Build failed!' }
    }
}
