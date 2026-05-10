package com.vayunmathur.youpipe.util
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

class MyDownloader : Downloader() {
    override fun execute(request: Request): Response = runBlocking {
        val url = request.url()
        val method = request.httpMethod()
        val body = request.dataToSend()

        val response = NetworkClient.performRequest(
            url = url,
            method = method,
            headers = request.headers(),
            body = body
        )

        Response(
            response.status,
            response.statusMessage,
            response.headers,
            response.body,
            response.url
        )
    }
}
