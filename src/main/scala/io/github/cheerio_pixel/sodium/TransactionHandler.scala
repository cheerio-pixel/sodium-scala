package io.github.cheerio_pixel.sodium

trait TransactionHandler[A] {
  def run(trans: Transaction, a: A): Unit
}
