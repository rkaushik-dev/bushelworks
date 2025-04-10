"""The exact pages the dot matrix prints.

These are the statements themselves, character for character, not a set of
assertions about them. A producer has been reading this form for thirty years
and the columns are where his eye goes, so a change to the layout is a change
to the product and it should show up here as a diff a person has to approve.

Three of them, because the interesting cases are at the edges: a wet load with
drying and an advance, a dry load with nothing but the checkoff, and a seven
figure settlement where the amount column is at its widest.

To regenerate after a deliberate layout change, print the rendered statement
for each fixture and paste it back. Do not hand edit these to make a test pass.
"""


WET = """\
                              BUSHELWORKS ELEVATOR
                              SETTLEMENT STATEMENT
================================================================================
Statement         S-2024-0417
Date              2024-10-20
Producer          MERRICK
Location          HOME
Tickets           A-10441
Contracts         F-2024-118
--------------------------------------------------------------------------------
GRAIN PURCHASED
  GRAIN       CORN 897.6324 bu at 4.3500                                3,904.70

DRYING
  DRY         drying 3.2 points                                         (102.33)

CHECKOFF WITHHELD
  KSCORN      Kansas Corn Commission                                      (8.98)

ADVANCES RECOVERED
  ADV-2024-09 advance of 2024-09-12                                   (1,500.00)

--------------------------------------------------------------------------------
  GROSS                                                                 3,904.70
  LESS DEDUCTIONS                                                       1,611.31
================================================================================
  NET PAYABLE                                                           2,293.39
"""


DRY = """\
                              BUSHELWORKS ELEVATOR
                              SETTLEMENT STATEMENT
================================================================================
Statement         S-2024-0417
Date              2024-10-20
Producer          MERRICK
Location          HOME
Tickets           A-10441
--------------------------------------------------------------------------------
GRAIN PURCHASED
  GRAIN       CORN 932.8571 bu at 4.3500                                4,057.93

CHECKOFF WITHHELD
  KSCORN      Kansas Corn Commission                                      (9.33)

--------------------------------------------------------------------------------
  GROSS                                                                 4,057.93
  LESS DEDUCTIONS                                                           9.33
================================================================================
  NET PAYABLE                                                           4,048.60
"""


BIG = """\
                              BUSHELWORKS ELEVATOR
                              SETTLEMENT STATEMENT
================================================================================
Statement         S-2024-0417
Date              2024-10-20
Producer          MERRICK
Location          HOME
Tickets           A-10441
--------------------------------------------------------------------------------
GRAIN PURCHASED
  GRAIN       CORN 924.7813 bu at 1450.0000                         1,340,932.89

DRYING
  DRY         drying 3.2 points                                         (105.43)

STORAGE
  STOR-L-1    storage on L-1 through 2024-10-20                         (412.50)

CHECKOFF WITHHELD
  KSCORN      Kansas Corn Commission                                      (9.25)

--------------------------------------------------------------------------------
  GROSS                                                             1,340,932.89
  LESS DEDUCTIONS                                                         527.18
================================================================================
  NET PAYABLE                                                       1,340,405.71
"""
