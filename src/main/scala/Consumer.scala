/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper
package silicon

import com.weiglewilczek.slf4s.Logging
import silver.verifier.PartialVerificationError
import silver.verifier.reasons.{NonPositivePermission, AssertionFalse}
import interfaces.state.{Store, Heap, PathConditions, State, StateFormatter, ChunkIdentifier, StateFactory}
import interfaces.{Consumer, Evaluator, VerificationResult, Failure}
import interfaces.decider.Decider
import reporting.{DefaultContext, Bookkeeper}
import state.{DirectChunk, DirectFieldChunk, DirectPredicateChunk, SymbolConvert}
import state.terms._
import state.terms.perms.{IsPositive, IsNoAccess}
import heap.QuantifiedChunkHelper

trait DefaultConsumer[ST <: Store[ST], H <: Heap[H],
											PC <: PathConditions[PC], S <: State[ST, H, S]]
		extends Consumer[DefaultFractionalPermissions, DirectChunk, ST, H, S, DefaultContext]
		{ this: Logging with Evaluator[DefaultFractionalPermissions, ST, H, S, DefaultContext]
									  with Brancher[ST, H, S, DefaultContext] =>

  private type C = DefaultContext
  private type P = DefaultFractionalPermissions

	protected val decider: Decider[P, ST, H, PC, S, C]
	import decider.assume

  protected val stateFactory: StateFactory[ST, H, S]
  import stateFactory._

  protected val symbolConverter: SymbolConvert
  import symbolConverter.toSort

  protected val quantifiedChunkHelper: QuantifiedChunkHelper[ST, H, PC, S, C]
	protected val stateFormatter: StateFormatter[ST, H, S, String]
	protected val bookkeeper: Bookkeeper
	protected val config: Config

  /*
   * ATTENTION: The DirectChunks passed to the continuation correspond to the
   * chunks as they existed when the consume took place. More specifically,
   * the amount of permissions that come with these chunks is NOT the amount
   * that has been consumed, but the amount that was found in the heap.
   */
	def consume(σ: S, p: P, φ: ast.Expression, pve: PartialVerificationError, c: C)
             (Q: (S, Term, List[DirectChunk], C) => VerificationResult)
             : VerificationResult =

    consume(σ, σ.h, p, φ.whenExhaling, pve, c)((h1, t, dcs, c1) =>
      Q(σ \ h1, t, dcs, c1))

  def consumes(σ: S,
               p: P,
               φs: Seq[ast.Expression],
               pvef: ast.Expression => PartialVerificationError,
               c: C)
              (Q: (S, List[Term], List[DirectChunk], C) => VerificationResult)
              : VerificationResult =

    consumes(σ, σ.h, p, φs map (_.whenExhaling), Nil, Nil, pvef, c)(Q)

  private def consumes(σ: S, h: H, p: P, φs: Seq[ast.Expression], ts: List[Term], dcs: List[DirectChunk], pvef: ast.Expression => PartialVerificationError, c: C)
                       (Q: (S, List[Term], List[DirectChunk], C) => VerificationResult)
                       : VerificationResult =

    if (φs.isEmpty)
      Q(σ \ h, ts.reverse, dcs.reverse, c)
    else
      consume(σ, h, p, φs.head, pvef(φs.head), c)((h1, t, dcs1, c1) =>
        consumes(σ, h1, p, φs.tail, t :: ts, dcs1 ::: dcs, pvef, c1)(Q))


  protected def consume(σ: S, h: H, p: P, φ: ast.Expression, pve: PartialVerificationError, c: C)
			                 (Q: (H, Term, List[DirectChunk], C) => VerificationResult)
                       : VerificationResult = {

    internalConsume(σ, h, p, φ, pve, c)((h1, s1, dcs, c1) => {
      Q(h1, s1, dcs, c1)
    })
  }

  private def internalConsume(σ: S, h: H, p: P, φ: ast.Expression, pve: PartialVerificationError, c: C)
                             (Q: (H, Term, List[DirectChunk], C) => VerificationResult)
                             : VerificationResult = {

    if (!φ.isInstanceOf[ast.And]) {
      logger.debug(s"\nCONSUME ${φ.pos}: $φ")
      logger.debug(stateFormatter.format(σ))
      logger.debug("h = " + stateFormatter.format(h))
    }

		val consumed = φ match {
      case ast.And(a1, a2) if !φ.isPure =>
				consume(σ, h, p, a1, pve, c)((h1, s1, dcs1, c1) =>
					consume(σ, h1, p, a2, pve, c1)((h2, s2, dcs2, c2) =>
//            println("\n[consumer/and]")
//            println(s"  φ = $φ")
//            println(s"  s1 = $s1}  (${s1.sort}, ${s1.getClass.getSimpleName}})")
//            println(s"  s2 = $s2}  (${s2.sort}, ${s2.getClass.getSimpleName}})")
            val s1a = s1 // s1.sort match {case _: sorts.Arrow => Select(s1, *()) case _ => s1} /* [SNAP-EQ] */
//            println(s"  s1a = $s1a  (${s1a.sort}, ${s1a.getClass.getSimpleName}})")
            val s2a = s2 // s2.sort match {case _: sorts.Arrow => Select(s2, *()) case _ => s2} /* [SNAP-EQ] */
//            println(s"  s2a = $s2a  (${s2a.sort}, ${s2a.getClass.getSimpleName}})")
						Q(h2, Combine(s1a, s2a), dcs1 ::: dcs2, c2)}))

      case ast.Implies(e0, a0) if !φ.isPure =>
				eval(σ, e0, pve, c)((t0, c1) =>
					branch(σ, t0, c,
						(c2: C) => consume(σ, h, p, a0, pve, c2)(Q),
						(c2: C) => Q(h, Unit, Nil, c2)))

      case ast.Ite(e0, a1, a2) if !φ.isPure =>
        eval(σ, e0, pve, c)((t0, c1) =>
          branch(σ, t0, c,
            (c2: C) => consume(σ, h, p, a1, pve, c2)(Q),
            (c2: C) => consume(σ, h, p, a2, pve, c2)(Q)))


      /* Quantified field access predicate */
      case ast.Forall(vars, triggers, ast.Implies(cond, ast.FieldAccessPredicate(locacc @ ast.FieldAccess(eRcvr, f), loss))) =>
        val tVars = vars map (v => decider.fresh(v.name, toSort(v.typ)))
        val γVars = Γ((vars map (v => ast.LocalVariable(v.name)(v.typ))) zip tVars)
        val σ0 = σ \+ γVars

        eval(σ0, cond, pve, c)((tCond, c1) => {
          /* We cheat a bit and syntactically rewrite the range; this should
           * not be needed if the axiomatisation supported it.
           */
          val rewrittenCond = quantifiedChunkHelper.rewriteGuard(tCond)
          if (decider.check(σ0, Not(rewrittenCond)))
            Q(h, Unit, Nil, c1)
          else {
            decider.assume(rewrittenCond)

this.asInstanceOf[DefaultEvaluator[ST, H, PC, C]].quantifiedVars = tVars ++: this.asInstanceOf[DefaultEvaluator[ST, H, PC, C]].quantifiedVars

            eval(σ0, eRcvr, pve, c1)((tRcvr, c2) =>
              evalp(σ0, loss, pve, c2)((tPerm, c3) => {

this.asInstanceOf[DefaultEvaluator[ST, H, PC, C]].quantifiedVars = this.asInstanceOf[DefaultEvaluator[ST, H, PC, C]].quantifiedVars.drop(tVars.length)

                decider.assert(σ, IsPositive(tPerm)){
                  case true =>
                    val h2 =
                      if (quantifiedChunkHelper.isQuantifiedFor(h, f.name)) h
                      else quantifiedChunkHelper.quantifyChunksForField(h, f.name)

                      quantifiedChunkHelper.value(σ, h2, tRcvr, f, tVars, pve, locacc, c3)(v => {
                        val t = v.t0
                        val ch = quantifiedChunkHelper.transform(tRcvr, f, t, tPerm * p, /* takes care of rewriting the cond */ tCond, tVars)
                        quantifiedChunkHelper.consume(σ, h2, None, f, ch.perm, pve, locacc, c3)(h3 =>
                          Q(h3, t, Nil, c3))})

                  case false =>
                    Failure[ST, H, S](pve dueTo NonPositivePermission(loss))}}))}})

      /* Field access predicates for quantified fields */
      case ast.AccessPredicate(locacc @ ast.FieldAccess(eRcvr, field), perm)
          if quantifiedChunkHelper.isQuantifiedFor(h, field.name) =>

        val ch = quantifiedChunkHelper.getQuantifiedChunk(h, field.name).get // TODO: Slightly inefficient, since it repeats the work of isQuantifiedFor

        eval(σ, eRcvr, pve, c)((tRcvr, c1) =>
          evalp(σ, perm, pve, c1)((tPerm, c2) =>
            quantifiedChunkHelper.value(σ, h, tRcvr, field, ch.quantifiedVars, pve, locacc, c2)(t => {
              val (ch1, optIdx) = quantifiedChunkHelper.transformElement(tRcvr, field.name, t, tPerm)
              quantifiedChunkHelper.consume(σ, h, Some(tRcvr), field, ch1.perm, pve, locacc, c2)(h2 =>
                Q(h2, t, Nil, c2))})))

      case ast.AccessPredicate(locacc, perm) =>
        withChunkIdentifier(σ, locacc, true, pve, c)((id, c1) =>
          evalp(σ, perm, pve, c1)((tPerm, c2) =>
            decider.assert(σ, IsPositive(tPerm)){
              case true =>
                consumePermissions(σ, h, id, p * tPerm, locacc, pve, c2)((h1, ch, c3, results) =>
                  ch match {
                    case fc: DirectFieldChunk =>
                      val snap = fc.value.convert(sorts.Snap)
                      Q(h1, snap, fc :: Nil, c3)

                    case pc: DirectPredicateChunk =>
                      val h2 =
                        if (results.consumedCompletely)
                          pc.nested.foldLeft(h1){case (ha, nc) => ha - nc}
                        else
                          h1
//                      println(s"  pc = $pc")
//                      println(s"  pc.snap = ${pc.snap}  (${pc.snap.sort}, ${pc.snap.getClass.getSimpleName}})")
                      Q(h2, pc.snap, pc :: Nil, c3)})
              case false =>
                Failure[ST, H, S](pve dueTo NonPositivePermission(perm))}))

      case _: ast.InhaleExhale =>
        Failure[ST, H, S](ast.Consistency.createUnexpectedInhaleExhaleExpressionError(φ))

			/* Any regular Expressions, i.e. boolean and arithmetic.
			 * IMPORTANT: The expression is evaluated in the initial heap (σ.h) and
			 * not in the partially consumed heap (h).
			 */
      case _ =>
        decider.tryOrFail[(H, Term, List[DirectChunk], C)](σ)((σ1, QS, QF) => {
          eval(σ1, φ, pve, c)((t, c) =>
//            println("\n[consume/pure]")
//            println(s"  φ = $φ")
//            println(s"  t = $t")
            decider.assert(σ1, t) {
              case true =>
                assume(t)
                QS((h, Unit, Nil, c))
              case false =>
                QF(Failure[ST, H, S](pve dueTo AssertionFalse(φ)))
            }})
        })(Q.tupled)
		}

		consumed
	}

  private def consumePermissions(σ: S,
                                 h: H,
                                 id: ChunkIdentifier,
                                 pLoss: P,
                                 locacc: ast.LocationAccess,
                                 pve: PartialVerificationError,
                                 c: C)
                                (Q:     (H, DirectChunk, C, PermissionsConsumptionResult)
                                     => VerificationResult)
                                :VerificationResult = {

    /* TODO: assert that pLoss > 0 */

    if (consumeExactRead(pLoss, c)) {
      decider.withChunk[DirectChunk](σ, h, id, pLoss, locacc, pve, c)(ch => {
        if (decider.check(σ, IsNoAccess(ch.perm - pLoss))) {
          Q(h - ch, ch, c, PermissionsConsumptionResult(true))}
        else
          Q(h - ch + (ch - pLoss), ch, c, PermissionsConsumptionResult(false))})
    } else {
      decider.withChunk[DirectChunk](σ, h, id, locacc, pve, c)(ch => {
        assume(pLoss < ch.perm)
        Q(h - ch + (ch - pLoss), ch, c, PermissionsConsumptionResult(false))})
    }
  }

  private def consumeExactRead(fp: P, c: C): Boolean = fp match {
    case TermPerm(v: Var) => !c.constrainableARPs.contains(v)
    case _: TermPerm => true
    case _: WildcardPerm => false
    case PermPlus(t0, t1) => consumeExactRead(t0, c) || consumeExactRead(t1, c)
    case PermMinus(t0, t1) => consumeExactRead(t0, c) || consumeExactRead(t1, c)
    case PermTimes(t0, t1) => consumeExactRead(t0, c) && consumeExactRead(t1, c)
    case IntPermTimes(_, t1) => consumeExactRead(t1, c)
    case _ => true
  }
}

private case class PermissionsConsumptionResult(consumedCompletely: Boolean)
