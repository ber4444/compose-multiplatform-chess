package com.example.myapplication.board3d

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.SceneKit.SCNGeometry
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNScene

@OptIn(ExperimentalForeignApi::class)
fun loadObjGeometryFromBytes(bytes: ByteArray, name: String): SCNGeometry? {
    if (bytes.isEmpty()) return null
    try {
        val data = bytes.usePinned { pinned ->
            platform.Foundation.NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        val tmpDir = NSTemporaryDirectory()
        val tempFilePath = "$tmpDir$name.obj"
        val url = NSURL.fileURLWithPath(tempFilePath)
        data.writeToFile(tempFilePath, atomically = true)
        
        val scene = SCNScene.sceneWithURL(url, options = null, error = null) ?: return null
        
        var geom: SCNGeometry? = null
        val rootNodes = scene.rootNode.childNodes
        for (i in 0 until rootNodes.size.toInt()) {
            val node = rootNodes[i] as? SCNNode ?: continue
            if (node.geometry != null) {
                geom = node.geometry
                break
            }
            val childNodes = node.childNodes
            for (j in 0 until childNodes.size.toInt()) {
                val child = childNodes[j] as? SCNNode ?: continue
                if (child.geometry != null) {
                    geom = child.geometry
                    break
                }
            }
            if (geom != null) break
        }
        
        return geom
    } catch (e: Exception) {
        return null
    }
}
