package com.hunorkovacs.koauth.sample.scala.consumerscalatra

import _root_.akka.actor.ActorSystem
import com.hunorkovacs.koauth.domain.OauthParams._
import com.hunorkovacs.koauth.domain.{TokenResponse, KoauthRequest}
import com.hunorkovacs.koauth.service.Arithmetics._
import com.hunorkovacs.koauth.service.consumer.DefaultConsumerService
import org.scalatra._
import spray.client.pipelining
import spray.client.pipelining._
import spray.http.HttpHeaders.RawHeader
import spray.http._
import spray.http.Rendering._


import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class Twitter extends ScalatraServlet {

  private val ConsumerKey = "TZO4QQGaFJBjXuKsyh8n1KrpE"
  private val ConsumerSecret = "7t57qMb1unIyiZrRVuWwSwAQ6QXxcUtuRh98iqeHQSjfXtFsIx"
  private val RequestTokenUrl = "https://api.twitter.com/oauth/request_token"
  private val AuthorizeTokenUrl = "https://api.twitter.com/oauth/authorize"
  private val AccessTokenUrl = "https://api.twitter.com/oauth/access_token"

  implicit private val system = ActorSystem()
  import system.dispatcher
  private val pipeline: HttpRequest => Future[HttpResponse] = sendReceive
  private val consumer = new DefaultConsumerService(system.dispatcher)
  private var token = TokenResponse("", "")

  get("/requestToken") {
    val requestTokenResponseF = consumer.createRequestTokenRequest(
        KoauthRequest("POST", RequestTokenUrl, None), ConsumerKey, ConsumerSecret,
          "http://127.0.0.1:8080/accessToken") flatMap { requestWithInfo =>
      pipeline(pipelining.Post(RequestTokenUrl).withHeaders(RawHeader("Authorization", requestWithInfo.header)))
    } map { response =>
      System.out.println("Response: " + response.entity.asString)
      parseRequestTokenResponse(response.entity.asString).right.get
    }
    token = Await.result(requestTokenResponseF, 4 seconds)
    redirect(AuthorizeTokenUrl + "?" + TokenName + "=" + token.token)
  }

  get("/accessToken") {
    val accessTokenResponseF = consumer.createAccessTokenRequest(
      KoauthRequest("POST", AccessTokenUrl, None), ConsumerKey, ConsumerSecret, token.token, token.secret,
        params(VerifierName)) flatMap { requestWithInfo =>
      pipeline(pipelining.Post(AccessTokenUrl).withHeaders(RawHeader("Authorization", requestWithInfo.header)))
    } map { response =>
      System.out.println("Response: " + response.entity.asString)
      parseAccessTokenResponse(response.entity.asString).right.get
    }
    token = Await.result(accessTokenResponseF, 4 seconds)
    redirect("/lastTweet")
  }

  get("/lastTweet") {
    val lastTweetUrl = "https://api.twitter.com/1.1/statuses/user_timeline.json?count=1&include_rts=1&trim_user=true"
    val responseF = sign(pipelining.Get(lastTweetUrl)).flatMap(pipeline(_))
    Await.result(responseF, 4 seconds).entity.asString
  }

  private def sign(request: HttpRequest) = {
    val body = request.headers
      .find(h => h.name == "Content-Type" && h.value == "application/x-www-form-urlencoded")
      .map(_ => request.entity.asString)
    consumer.createOauthenticatedRequest(KoauthRequest(request.method.value, request.uri.toString(), None, body),
        ConsumerKey, ConsumerSecret, token.token, token.secret) map { requestWithInfo =>
      request.withHeaders(RawHeader("Authorization", requestWithInfo.header))
    }
  }
}
