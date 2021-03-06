/*
 * Copyright (c) 2018. B3i Switzerland. All rights reserved.
 *
 * http://www.b3i.tech
 */
package tech.b3i.network.map.filesystem

import com.typesafe.config.ConfigFactory
import net.corda.core.identity.Party
import net.corda.core.internal.readObject
import net.corda.core.node.NodeInfo
import net.corda.core.node.NotaryInfo
import net.corda.nodeapi.internal.SignedNodeInfo
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.RegexFileFilter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.util.ResourceUtils
import tech.b3i.network.map.NotaryInfoLoader


@Component
class FilesystemNotaryInfoLoader(
        @Value("\${nodesDirectoryUrl:classpath:nodes}") private val nodesDirectoryUrl: String)
    : NotaryInfoLoader {

    override fun load(): List<NotaryInfo> {
        val nodesDirectory = ResourceUtils.getFile(nodesDirectoryUrl)
        log.info("Started scanning nodes directory ${nodesDirectory.absolutePath} for notaries in node.conf files")
        val configFiles = FileUtils.listFiles(
                nodesDirectory,
                RegexFileFilter("node.conf"),
                DirectoryFileFilter.DIRECTORY
        )
        log.info("Found ${configFiles.size} node.conf files")

        val notaries = configFiles
                .mapNotNull { ConfigFactory.parseFile(it) to it }
                .filter { it.first.hasPath("notary") }
                .map { (notaryNodeConf, notaryNodeConfFile) ->
                    val validating = notaryNodeConf.getConfig("notary").getBoolean("validating")
                    FileUtils.listFiles(
                            notaryNodeConfFile.parentFile,
                            RegexFileFilter("nodeInfo-.*"),
                            null)
                            .firstOrNull() to validating
                }
                .filter { it.first != null }
                .map {
                    val nodeInfo = it.first!!.toPath()!!.readObject<SignedNodeInfo>().verified()
                    log.debug("found notary: ${nodeInfo.legalIdentities} @ ${nodeInfo.addresses}")
                    NotaryInfo(nodeInfo.notaryIdentity(), validating = it.second)
                }
        log.info("Found ${notaries.size} notaries in ${nodesDirectory.absolutePath}")
        return notaries
    }

    companion object {
        private val log = LoggerFactory.getLogger(FilesystemNotaryInfoLoader::class.java)
    }

}


private fun NodeInfo.notaryIdentity(): Party {
    return when (legalIdentities.size) {
    // Single node notaries have just one identity like all other nodes. This identity is the notary identity
        1 -> legalIdentities[0]
    // Nodes which are part of a distributed notary have a second identity which is the composite identity of the
    // cluster and is shared by all the other members. This is the notary identity.
        2 -> legalIdentities[1]
        else -> throw IllegalArgumentException("Not sure how to get the notary identity in this scenario: $this")
    }
}

