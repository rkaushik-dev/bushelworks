"""Use cases that run a ticket through the domain in the right order.

The domain modules each know one rule. A service knows the sequence, and the
sequence is where the money is: shrink before discounts, discounts before
assessments, assessments before advances. Nothing in here holds state between
calls.
"""
