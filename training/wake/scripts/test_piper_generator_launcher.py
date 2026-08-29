"""Focused tests for the Piper launcher's argument boundary."""

from __future__ import annotations

import unittest

from piper_generator_launcher import _forwarded_generator_arguments


class ForwardedGeneratorArgumentsTests(unittest.TestCase):
    def test_strips_the_launcher_separator(self) -> None:
        self.assertEqual(
            _forwarded_generator_arguments(["--", "hey foresight", "--max-samples", "5"]),
            ["hey foresight", "--max-samples", "5"],
        )

    def test_preserves_arguments_without_a_separator(self) -> None:
        self.assertEqual(
            _forwarded_generator_arguments(["hey foresight", "--max-samples", "5"]),
            ["hey foresight", "--max-samples", "5"],
        )
