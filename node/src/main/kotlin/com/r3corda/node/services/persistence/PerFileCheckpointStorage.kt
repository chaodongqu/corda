package com.r3corda.node.services.persistence

import com.r3corda.core.serialization.SerializedBytes
import com.r3corda.core.serialization.deserialize
import com.r3corda.core.serialization.serialize
import com.r3corda.core.utilities.loggerFor
import com.r3corda.core.utilities.trace
import com.r3corda.node.services.api.Checkpoint
import com.r3corda.node.services.api.CheckpointStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.Collections.synchronizedMap
import javax.annotation.concurrent.ThreadSafe


/**
 * File-based checkpoint storage, storing checkpoints per file.
 */
@ThreadSafe
class PerFileCheckpointStorage(val storeDir: Path) : CheckpointStorage {

    companion object {
        private val logger = loggerFor<PerFileCheckpointStorage>()
        private val fileExtension = ".checkpoint"
    }

    private val checkpointFiles = synchronizedMap(IdentityHashMap<Checkpoint, Path>())

    init {
        logger.trace { "Initialising per file checkpoint storage on $storeDir" }
        Files.createDirectories(storeDir)
        Files.list(storeDir)
                .filter { it.toString().toLowerCase().endsWith(fileExtension) }
                .forEach {
                    val checkpoint = Files.readAllBytes(it).deserialize<Checkpoint>()
                    checkpointFiles[checkpoint] = it
                }
    }

    override fun addCheckpoint(checkpoint: Checkpoint) {
        val fileName = "${checkpoint.id.toString().toLowerCase()}$fileExtension"
        val checkpointFile = storeDir.resolve(fileName)
        atomicWrite(checkpointFile, checkpoint.serialize())
        logger.trace { "Stored $checkpoint to $checkpointFile" }
        checkpointFiles[checkpoint] = checkpointFile
    }

    private fun atomicWrite(checkpointFile: Path, serialisedCheckpoint: SerializedBytes<Checkpoint>) {
        val tempCheckpointFile = checkpointFile.parent.resolve("${checkpointFile.fileName}.tmp")
        serialisedCheckpoint.writeToFile(tempCheckpointFile)
        Files.move(tempCheckpointFile, checkpointFile, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun removeCheckpoint(checkpoint: Checkpoint) {
        val checkpointFile = checkpointFiles.remove(checkpoint)
        require(checkpointFile != null) { "Trying to removing unknown checkpoint: $checkpoint" }
        Files.delete(checkpointFile)
        logger.trace { "Removed $checkpoint ($checkpointFile)" }
    }

    override fun forEach(block: (Checkpoint)->Boolean) {
        synchronized(checkpointFiles) {
            for(checkpoint in checkpointFiles.keys) {
                if (!block(checkpoint)) {
                    break
                }
            }
        }
    }

}