def katello_versions = [
    'master': [
        'foreman': 'develop',
        'ruby': '2.5',
    ],
    'KATELLO-3.16': [
        'foreman': '2.1-stable',
        'ruby': '2.5'
    ],
    'KATELLO-3.15': [
        'foreman': '2.0-stable',
        'ruby': '2.5'
    ],
    'KATELLO-3.14': [
        'foreman': '1.24-stable',
        'ruby': '2.5'
    ],
    'KATELLO-3.13': [
        'foreman': '1.23-stable',
        'ruby': '2.5'
    ],
]

def ruby = katello_versions[ghprbTargetBranch]['ruby']
def foreman_branch = katello_versions[ghprbTargetBranch]['foreman']

pipeline {
    options {
        timestamps()
        timeout(time: 2, unit: 'HOURS')
        ansiColor('xterm')
    }

    agent { label 'fast' }

    stages {
        stage('Setup Git Repos') {
            steps {
                deleteDir()
                ghprb_git_checkout()

                dir('foreman') {
                   git url: "https://github.com/theforeman/foreman", branch: foreman_branch, poll: false
                }
            }
        }
        stage("Setup RVM") {
            steps {

                configureRVM(ruby)

            }
        }
        stage('Configure Environment') {
            steps {

                dir('foreman') {
                    addGem()
                    databaseFile(gemset())
                }

            }
        }
        stage('Configure Database') {
            steps {

                dir('foreman') {
                    configureDatabase(ruby)
                }

            }
        }
        stage('Install Foreman npm packages') {
            steps {
                dir('foreman') {
                    withRVM(["bundle exec npm install"], ruby)
                }
            }
        }
        stage('Run Tests') {
            parallel {
                stage('tests') {
                    steps {
                        dir('foreman') {
                            withRVM(['bundle exec rake jenkins:katello TESTOPTS="-v" --trace'], ruby)
                        }
                    }
                }
                stage('rubocop') {
                    steps {
                        dir('foreman') {
                            withRVM(['bundle exec rake katello:rubocop TESTOPTS="-v" --trace'], ruby)
                        }
                    }
                }
                stage('react-ui') {
                    when {
                        expression { fileExists('package.json') }
                    }
                    steps {
                        sh "npm install"
                        sh 'npm test'
                    }
                }
                stage('angular-ui') {
                    steps {
                        script {
                            if (!fileExists('engines/bastion')) {
                                dir('foreman') {
                                    withRVM(['bundle show bastion > bastion-version'], ruby)

                                    script {
                                        bastion_install = readFile('bastion-version')
                                        bastion_version = bastion_install.split('bastion-')[1]
                                        echo bastion_install
                                        echo bastion_version
                                    }
                                }

                                sh "cp -rf \$(cat foreman/bastion-version) engines/bastion_katello/bastion-${bastion_version}"
                                dir('engines/bastion_katello') {
                                    sh "npm install bastion-${bastion_version}"
                                    sh "grunt ci"
                                }
                            } else {
                                dir('engines/bastion') {
                                    sh "npm install"
                                    sh "grunt ci"
                                }
                                dir('engines/bastion_katello') {
                                    sh "npm install"
                                    sh "grunt ci"
                                }
                            }
                        }
                    }
                }
                stage('assets-precompile') {
                    steps {
                        dir('foreman') {
                            withRVM(['bundle exec rake plugin:assets:precompile[katello] RAILS_ENV=production DATABASE_URL=nulldb://nohost --trace'], ruby)
                        }
                    }
                }
            }
            post {
                always {
                    dir('foreman') {
                        archiveArtifacts artifacts: "log/test.log"
                        junit keepLongStdio: true, testResults: 'jenkins/reports/unit/*.xml'
                    }
                }
            }
        }
        stage('Test db:seed') {
            steps {

                dir('foreman') {

                    withRVM(['bundle exec rake db:drop RAILS_ENV=test || true'], ruby)
                    withRVM(['bundle exec rake db:create RAILS_ENV=test'], ruby)
                    withRVM(['bundle exec rake db:migrate RAILS_ENV=test'], ruby)
                    withRVM(['bundle exec rake db:seed RAILS_ENV=test'], ruby)

                }

            }
        }
    }

    post {
        always {
            dir('foreman') {
                cleanup(ruby)
            }
            deleteDir()
        }
    }
}
