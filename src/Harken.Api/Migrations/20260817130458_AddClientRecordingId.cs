using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Harken.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddClientRecordingId : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<Guid>(
                name: "ClientRecordingId",
                table: "Sessions",
                type: "TEXT",
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "IX_Sessions_ClientRecordingId",
                table: "Sessions",
                column: "ClientRecordingId",
                unique: true,
                filter: "\"ClientRecordingId\" IS NOT NULL");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_Sessions_ClientRecordingId",
                table: "Sessions");

            migrationBuilder.DropColumn(
                name: "ClientRecordingId",
                table: "Sessions");
        }
    }
}
