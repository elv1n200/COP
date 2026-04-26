package com.github.noamm9.critsaddons.commands.impl

import com.github.noamm9.commands.BaseCommand
import com.github.noamm9.commands.CommandNodeBuilder
import com.github.noamm9.critsaddons.features.impl.critsaddons.SecretRoutes

object SecretRouteCommand: BaseCommand("nsr") {
    override fun CommandNodeBuilder.build() {
        literal("save") {
            runs {
                SecretRoutes.saveRecording()
            }
        }

        literal("cancel") {
            runs {
                SecretRoutes.cancelRecording()
            }
        }

        literal("continue") {
            runs {
                SecretRoutes.continueRecording()
            }
        }

        literal("complete") {
            runs {
                SecretRoutes.markCurrentRoomCompleted()
            }
        }

        literal("wait") {
            runs {
                SecretRoutes.insertWaitStep()
            }
        }

        literal("start") {
            literal("delete") {
                runs {
                    SecretRoutes.deleteStartLinkFromCurrentBlock()
                }
            }
            runs {
                SecretRoutes.startStartPathRecording()
            }
        }

        literal("bat") {
            runs {
                SecretRoutes.insertBatWaitStep()
            }
        }

        literal("kill") {
            runs {
                SecretRoutes.killDuringRecording()
            }
        }

        literal("add") {
            literal("ew") {
                runs { SecretRoutes.addEtherwarpStep() }
            }
            literal("etherwarp") {
                runs { SecretRoutes.addEtherwarpStep() }
            }
            literal("tnt") {
                runs { SecretRoutes.addTntStep() }
            }
            literal("break") {
                runs { SecretRoutes.addBreakStep() }
            }
            literal("hyp") {
                runs { SecretRoutes.addHyperionStep() }
            }
            literal("hyperion") {
                runs { SecretRoutes.addHyperionStep() }
            }
            literal("secret") {
                runs { SecretRoutes.addSecretStep() }
            }
            literal("wait") {
                runs { SecretRoutes.insertWaitStep() }
            }
            literal("bat") {
                runs { SecretRoutes.insertBatWaitStep() }
            }
        }

        literal("end") {
            literal("delete") {
                runs {
                    SecretRoutes.deleteEndLinkFromCurrentBlock()
                }
            }
            literal("helper") {
                runs {
                    SecretRoutes.addEndHelperBlockFromCurrentBlock()
                }
            }
            runs {
                SecretRoutes.addEndBlockFromCurrentBlock()
            }
        }

        literal("delete") {
            runs {
                SecretRoutes.deleteCurrentRoomRoute()
            }
        }

        runs {
            SecretRoutes.startRecording()
        }
    }
}
